package lila.api

import org.joda.time.DateTime
import play.api.libs.json._
import reactivemongo.api.ReadPreference
import reactivemongo.bson._
import scala.concurrent.duration._

import lila.analyse.{ JsonView => analysisJson, AnalysisRepo, Analysis }
import lila.common.paginator.{ Paginator, PaginatorJson }
import lila.db.dsl._
import lila.db.paginator.{ Adapter, CachedAdapter }
import lila.game.BSONHandlers._
import lila.game.Game.{ BSONFields => G }
import lila.game.{ Game, GameRepo, PerfPicker, CrosstableApi }
import lila.user.User

private[api] final class GameApi(
    netBaseUrl: String,
    apiToken: String,
    pgnDump: PgnDump,
    gameCache: lila.game.Cached,
    crosstableApi: CrosstableApi
) {

  import GameApi.WithFlags
  import lila.game.JsonView.openingWriter

  def byUser(
    user: User,
    rated: Option[Boolean],
    playing: Option[Boolean],
    analysed: Option[Boolean],
    withFlags: WithFlags,
    nb: Int,
    page: Int
  ): Fu[JsObject] = Paginator(
    adapter = new CachedAdapter(
      adapter = new Adapter[Game](
        collection = GameRepo.coll,
        selector = {
          if (~playing) lila.game.Query.nowPlaying(user.id)
          else $doc(
            G.playerUids -> user.id,
            G.status $gte chess.Status.Mate.id,
            G.analysed -> analysed.map(_.fold[BSONValue](BSONBoolean(true), $doc("$exists" -> false)))
          )
        } ++ $doc(
          G.rated -> rated.map(_.fold[BSONValue](BSONBoolean(true), $doc("$exists" -> false)))
        ),
        projection = $empty,
        sort = $doc(G.createdAt -> -1),
        readPreference = ReadPreference.secondaryPreferred
      ),
      nbResults =
        if (~playing) gameCache.nbPlaying(user.id)
        else fuccess {
          rated.fold(user.count.game)(_.fold(user.count.rated, user.count.casual))
        }
    ),
    currentPage = page,
    maxPerPage = nb
  ) flatMap { pag =>
      gamesJson(withFlags = withFlags)(pag.currentPageResults) map { games =>
        PaginatorJson(pag withCurrentPageResults games)
      }
    }

  def one(id: String, withFlags: WithFlags): Fu[Option[JsObject]] =
    GameRepo game id flatMap {
      _ ?? { g =>
        gamesJson(withFlags)(List(g)) map (_.headOption)
      }
    }

  def many(ids: Seq[String], withMoves: Boolean): Fu[Seq[JsObject]] =
    GameRepo gamesFromPrimary ids flatMap {
      gamesJson(WithFlags(moves = withMoves)) _
    }

  def byUsersVs(
    users: (User, User),
    rated: Option[Boolean],
    playing: Option[Boolean],
    analysed: Option[Boolean],
    withFlags: WithFlags,
    nb: Int,
    page: Int
  ): Fu[JsObject] = Paginator(
    adapter = new CachedAdapter(
      adapter = new Adapter[Game](
        collection = GameRepo.coll,
        selector = {
          if (~playing) lila.game.Query.nowPlayingVs(users._1.id, users._2.id)
          else lila.game.Query.opponents(users._1, users._2) ++ $doc(
            G.status $gte chess.Status.Mate.id,
            G.analysed -> analysed.map(_.fold[BSONValue](BSONBoolean(true), $doc("$exists" -> false)))
          )
        } ++ $doc(
          G.rated -> rated.map(_.fold[BSONValue](BSONBoolean(true), $doc("$exists" -> false)))
        ),
        projection = $empty,
        sort = $doc(G.createdAt -> -1),
        readPreference = ReadPreference.secondaryPreferred
      ),
      nbResults =
        if (~playing) gameCache.nbPlaying(users._1.id)
        else crosstableApi(users._1.id, users._2.id, 5 seconds).map { _ ?? (_.nbGames) }
    ),
    currentPage = page,
    maxPerPage = nb
  ) flatMap { pag =>
      gamesJson(withFlags.copy(fens = false))(pag.currentPageResults) map { games =>
        PaginatorJson(pag withCurrentPageResults games)
      }
    }

  def byUsersVs(
    userIds: Iterable[User.ID],
    rated: Option[Boolean],
    playing: Option[Boolean],
    analysed: Option[Boolean],
    withFlags: WithFlags,
    since: DateTime,
    nb: Int,
    page: Int
  ): Fu[JsObject] = Paginator(
    adapter = new Adapter[Game](
      collection = GameRepo.coll,
      selector = {
        if (~playing) lila.game.Query.nowPlayingVs(userIds)
        else lila.game.Query.opponents(userIds) ++ $doc(
          G.status $gte chess.Status.Mate.id,
          G.analysed -> analysed.map(_.fold[BSONValue](BSONBoolean(true), $doc("$exists" -> false)))
        )
      } ++ $doc(
        G.rated -> rated.map(_.fold[BSONValue](BSONBoolean(true), $doc("$exists" -> false))),
        G.createdAt $gte since
      ),
      projection = $empty,
      sort = $doc(G.createdAt -> -1),
      readPreference = ReadPreference.secondaryPreferred
    ),
    currentPage = page,
    maxPerPage = nb
  ) flatMap { pag =>
      gamesJson(withFlags.copy(fens = false))(pag.currentPageResults) map { games =>
        PaginatorJson(pag withCurrentPageResults games)
      }
    }

  private def makeUrl(game: Game) = s"$netBaseUrl/${game.id}/${game.firstPlayer.color.name}"

  private def gamesJson(withFlags: WithFlags)(games: Seq[Game]): Fu[Seq[JsObject]] = {
    val allAnalysis =
      if (withFlags.analysis) AnalysisRepo byIds games.map(_.id)
      else fuccess(List.fill(games.size)(none[Analysis]))
    allAnalysis flatMap { analysisOptions =>
      (games map GameRepo.initialFen).sequenceFu map { initialFens =>
        games zip analysisOptions zip initialFens map {
          case ((g, analysisOption), initialFen) =>
            gameToJson(g, analysisOption, initialFen, checkToken(withFlags))
        }
      }
    }
  }

  private def checkToken(withFlags: WithFlags) = withFlags applyToken apiToken

  private def gameToJson(
    g: Game,
    analysisOption: Option[Analysis],
    initialFen: Option[String],
    withFlags: WithFlags
  ) = Json.obj(
    "id" -> g.id,
    "initialFen" -> initialFen,
    "rated" -> g.rated,
    "variant" -> g.variant.key,
    "speed" -> g.speed.key,
    "perf" -> PerfPicker.key(g),
    "createdAt" -> g.createdAt,
    "lastMoveAt" -> g.movedAt,
    "turns" -> g.turns,
    "color" -> g.turnColor.name,
    "status" -> g.status.name,
    "clock" -> g.clock.map { clock =>
      Json.obj(
        "initial" -> clock.limitSeconds,
        "increment" -> clock.incrementSeconds,
        "totalTime" -> clock.estimateTotalSeconds
      )
    },
    "daysPerTurn" -> g.daysPerTurn,
    "players" -> JsObject(g.players map { p =>
      p.color.name -> Json.obj(
        "userId" -> p.userId,
        "name" -> p.name,
        "rating" -> p.rating,
        "ratingDiff" -> p.ratingDiff
      ).add("provisional" -> p.provisional)
        .add("moveCentis" -> withFlags.moveTimes ?? g.moveTimes(p.color).map(_.map(_.centis)))
        .add("blurs" -> withFlags.blurs.option(p.blurs.nb))
        .add("hold" -> p.holdAlert.ifTrue(withFlags.hold).map { h =>
          Json.obj(
            "ply" -> h.ply,
            "mean" -> h.mean,
            "sd" -> h.sd
          )
        })
        .add("analysis" -> analysisOption.flatMap(analysisJson.player(g pov p.color)))
    }),
    "analysis" -> analysisOption.ifTrue(withFlags.analysis).map(analysisJson.moves),
    "moves" -> withFlags.moves.option(g.pgnMoves mkString " "),
    "opening" -> withFlags.opening.??(g.opening),
    "fens" -> (withFlags.fens && g.finished) ?? {
      chess.Replay.boards(
        moveStrs = g.pgnMoves,
        initialFen = initialFen map chess.format.FEN,
        variant = g.variant
      ).toOption map { boards =>
        JsArray(boards map chess.format.Forsyth.exportBoard map JsString.apply)
      }
    },
    "winner" -> g.winnerColor.map(_.name),
    "url" -> makeUrl(g)
  ).noNull
}

object GameApi {

  case class WithFlags(
      analysis: Boolean = false,
      moves: Boolean = false,
      fens: Boolean = false,
      opening: Boolean = false,
      moveTimes: Boolean = false,
      blurs: Boolean = false,
      hold: Boolean = false,
      token: Option[String] = none
  ) {

    def applyToken(validToken: String) = copy(
      blurs = token has validToken,
      hold = token has validToken
    )
  }
}
