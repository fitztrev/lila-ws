package lila.ws

import chess.Color
import com.github.blemale.scaffeine.{ AsyncLoadingCache, Scaffeine }
import com.typesafe.config.Config
import org.joda.time.DateTime
import reactivemongo.api.bson.*
import reactivemongo.api.bson.collection.BSONCollection
import reactivemongo.api.{ AsyncDriver, DB, MongoConnection, ReadConcern, ReadPreference }
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.parasitic
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Success, Try }

final class Mongo(config: Config)(using executionContext: ExecutionContext) extends MongoHandlers:

  private val driver = new AsyncDriver(Some(config.getConfig("reactivemongo")))

  private val mainConnection =
    MongoConnection.fromString(config.getString("mongo.uri")) flatMap { parsedUri =>
      driver.connect(parsedUri).map(_ -> parsedUri.db)
    }
  private def mainDb: Future[DB] =
    mainConnection flatMap { case (conn, dbName) =>
      conn database dbName.getOrElse("lichess")
    }

  private val studyConnection =
    MongoConnection.fromString(config.getString("study.mongo.uri")) flatMap { parsedUri =>
      driver.connect(parsedUri).map(_ -> parsedUri.db)
    }
  private def studyDb: Future[DB] =
    studyConnection flatMap { case (conn, dbName) =>
      conn database dbName.getOrElse("lichess")
    }

  private def collNamed(name: String) = mainDb.map(_ collection name)(parasitic)
  def securityColl                    = collNamed("security")
  def userColl                        = collNamed("user4")
  def coachColl                       = collNamed("coach")
  def streamerColl                    = collNamed("streamer")
  def simulColl                       = collNamed("simul")
  def tourColl                        = collNamed("tournament2")
  def tourPlayerColl                  = collNamed("tournament_player")
  def tourPairingColl                 = collNamed("tournament_pairing")
  def gameColl                        = collNamed("game5")
  def challengeColl                   = collNamed("challenge")
  def relationColl                    = collNamed("relation")
  def teamColl                        = collNamed("team")
  def teamMemberColl                  = collNamed("team_member")
  def swissColl                       = collNamed("swiss")
  def reportColl                      = collNamed("report2")
  def studyColl                       = studyDb.map(_ collection "study")(parasitic)

  def security[A](f: BSONCollection => Future[A]): Future[A] = securityColl flatMap f
  def coach[A](f: BSONCollection => Future[A]): Future[A]    = coachColl flatMap f
  def streamer[A](f: BSONCollection => Future[A]): Future[A] = streamerColl flatMap f
  def user[A](f: BSONCollection => Future[A]): Future[A]     = userColl flatMap f

  def simulExists(id: Simul.ID): Future[Boolean] = simulColl flatMap idExists(id)

  private def isTeamMember(teamId: Team.ID, user: UserId): Future[Boolean] =
    teamMemberColl flatMap { exists(_, BSONDocument("_id" -> s"${user.value}@$teamId")) }

  def teamView(id: Team.ID, me: Option[UserId]): Future[Option[Team.View]] = {
    teamColl flatMap {
      _.find(
        selector = BSONDocument("_id" -> id),
        projection = Some(BSONDocument("chat" -> true, "leaders" -> true))
      ).one[BSONDocument]
    } zip me.fold(Future successful false) { isTeamMember(id, _) }
  } map {
    case (None, _)  => None
    case (_, false) => Some(Team.View(hasChat = false))
    case (Some(teamDoc), true) =>
      Some(
        Team.View(
          hasChat = teamDoc.int("chat").fold(false) { chat =>
            chat == Team.Access.Members.id ||
            (chat == Team.Access.Leaders.id && me.fold(false) { me =>
              teamDoc.getAsOpt[Set[UserId]]("leaders").exists(_ contains me)
            })
          }
        )
      )
  }

  def swissExists(id: Swiss.ID): Future[Boolean] = swissColl flatMap idExists(id)

  def tourExists(id: Tour.ID): Future[Boolean] = tourColl flatMap idExists(id)

  def studyExists(id: Study.ID): Future[Boolean] = studyColl flatMap idExists(id)

  def gameExists(id: Game.Id): Future[Boolean] =
    gameCache getIfPresent id match
      case None        => gameColl flatMap idExists(id.value)
      case Some(entry) => entry.map(_.isDefined)(parasitic)

  def player(fullId: Game.FullId, user: Option[UserId]): Future[Option[Game.RoundPlayer]] =
    gameCache
      .get(fullId.gameId)
      .map {
        _ flatMap {
          _.player(fullId.playerId, user)
        }
      }(parasitic)

  private val gameCacheProjection =
    BSONDocument("is" -> true, "us" -> true, "tid" -> true, "sid" -> true, "iid" -> true)

  private val gameCache: AsyncLoadingCache[Game.Id, Option[Game.Round]] = Scaffeine()
    .expireAfterWrite(10.minutes)
    .buildAsyncFuture { id =>
      gameColl flatMap {
        _.find(
          selector = BSONDocument("_id" -> id.value),
          projection = Some(gameCacheProjection)
        ).one[BSONDocument]
          .map { docOpt =>
            for {
              doc       <- docOpt
              playerIds <- doc.getAsOpt[String]("is")
              users = doc.getAsOpt[List[UserId]]("us") getOrElse Nil
              players = Color.Map(
                Game.Player(Game.PlayerId(playerIds take 4), users.headOption.filter(_.value.nonEmpty)),
                Game.Player(Game.PlayerId(playerIds drop 4), users lift 1)
              )
              ext =
                doc.getAsOpt[Tour.ID]("tid").map(Game.RoundExt.Tour.apply) orElse
                  doc.getAsOpt[Swiss.ID]("iid").map(Game.RoundExt.Swiss.apply) orElse
                  doc.getAsOpt[Simul.ID]("sid").map(Game.RoundExt.Simul.apply)
            } yield Game.Round(id, players, ext)
          }(parasitic)
      }
    }

  private val visibilityNotPrivate = BSONDocument("visibility" -> BSONDocument("$ne" -> "private"))

  def studyExistsFor(id: Simul.ID, user: Option[UserId]): Future[Boolean] =
    studyColl flatMap {
      exists(
        _,
        BSONDocument(
          "_id" -> id,
          user.fold(visibilityNotPrivate) { u =>
            BSONDocument(
              "$or" -> BSONArray(
                visibilityNotPrivate,
                BSONDocument(s"members.${u.value}" -> BSONDocument("$exists" -> true))
              )
            )
          }
        )
      )
    }

  def studyMembers(id: Study.ID): Future[Set[UserId]] =
    studyColl flatMap {
      _.find(
        selector = BSONDocument("_id" -> id),
        projection = Some(BSONDocument("members" -> true))
      ).one[BSONDocument] map { docOpt =>
        for {
          doc     <- docOpt
          members <- doc.getAsOpt[BSONDocument]("members")
        } yield members.elements.collect { case BSONElement(key, _) => UserId(key) }.toSet
      } map (_ getOrElse Set.empty)
    }

  def tournamentActiveUsers(tourId: Tour.ID): Future[Set[UserId]] =
    tourPlayerColl flatMap {
      _.distinct[UserId, Set](
        key = "uid",
        selector = Some(BSONDocument("tid" -> tourId, "w" -> BSONDocument("$ne" -> true))),
        readConcern = ReadConcern.Local,
        collation = None
      )
    }

  def tournamentPlayingUsers(tourId: Tour.ID): Future[Set[UserId]] =
    tourPairingColl flatMap {
      _.distinct[UserId, Set](
        key = "u",
        selector = Some(BSONDocument("tid" -> tourId, "s" -> BSONDocument("$lt" -> chess.Status.Mate.id))),
        readConcern = ReadConcern.Local,
        collation = None
      )
    }

  def challenger(challengeId: Challenge.Id): Future[Option[Challenge.Challenger]] =
    challengeColl flatMap {
      _.find(
        selector = BSONDocument("_id" -> challengeId.value),
        projection = Some(BSONDocument("challenger" -> true))
      ).one[BSONDocument] map {
        _.flatMap {
          _.getAsOpt[BSONDocument]("challenger")
        } map { c =>
          val anon = c.getAsOpt[String]("s") map Challenge.Challenger.Anon.apply
          val user = c.getAsOpt[String]("id") map Challenge.Challenger.User.apply
          anon orElse user getOrElse Challenge.Challenger.Open
        }
      }
    }

  def inquirers: Future[List[UserId]] =
    reportColl flatMap {
      _.distinct[UserId, List](
        key = "inquiry.mod",
        selector = Some(BSONDocument("inquiry.mod" -> BSONDocument("$exists" -> true))),
        readConcern = ReadConcern.Local,
        collation = None
      )
    }

  private val userDataProjection =
    BSONDocument("username" -> true, "title" -> true, "plan" -> true, "_id" -> false)
  private def userDataReader(doc: BSONDocument) =
    for {
      name <- doc.getAsOpt[String]("username")
      title  = doc.getAsOpt[String]("title")
      patron = doc.child("plan").flatMap(_.getAsOpt[Boolean]("active")) getOrElse false
    } yield FriendList.UserData(name, title, patron)

  def loadFollowed(userId: UserId): Future[Iterable[UserId]] =
    relationColl flatMap {
      _.distinct[UserId, List](
        key = "u2",
        selector = Some(BSONDocument("u1" -> userId, "r" -> true)),
        readConcern = ReadConcern.Local,
        collation = None
      )
    }

  def userData(userId: UserId): Future[Option[FriendList.UserData]] =
    userColl flatMap {
      _.find(
        BSONDocument("_id" -> userId),
        Some(userDataProjection)
      ).one[BSONDocument](readPreference = ReadPreference.secondaryPreferred)
        .map { _ flatMap userDataReader }
    }

  object troll:

    def is(user: Option[UserId]): Future[IsTroll] =
      user.fold(Future successful IsTroll(false)) { u =>
        cache.get(u).map(IsTroll.apply)(parasitic)
      }

    def set(userId: UserId, v: IsTroll): Unit =
      cache.put(userId, Future successful v.value)

    private val cache: AsyncLoadingCache[UserId, Boolean] = Scaffeine()
      .expireAfterAccess(20.minutes)
      .buildAsyncFuture { id =>
        userColl flatMap { exists(_, BSONDocument("_id" -> id, "marks" -> "troll")) }
      }

  object idFilter:
    val study: IdFilter = ids => studyColl flatMap filterIds(ids)
    val tour: IdFilter  = ids => tourColl flatMap filterIds(ids)
    val simul: IdFilter = ids => simulColl flatMap filterIds(ids)
    val team: IdFilter  = ids => teamColl flatMap filterIds(ids)
    val swiss: IdFilter = ids => swissColl flatMap filterIds(ids)

  private def idExists(id: String)(coll: BSONCollection): Future[Boolean] =
    exists(coll, BSONDocument("_id" -> id))

  private def exists(coll: BSONCollection, selector: BSONDocument): Future[Boolean] =
    coll
      .count(
        selector = Some(selector),
        limit = None,
        skip = 0,
        hint = None,
        readConcern = ReadConcern.Local
      )
      .map(0 < _)(parasitic)

  private def filterIds(ids: Iterable[String])(coll: BSONCollection): Future[Set[String]] =
    coll.distinct[String, Set](
      key = "_id",
      selector = Some(BSONDocument("_id" -> BSONDocument("$in" -> ids))),
      readConcern = ReadConcern.Local,
      collation = None
    )

trait MongoHandlers:

  type IdFilter = Iterable[String] => Future[Set[String]]

  given dateHandler: BSONHandler[DateTime] with
    @inline def readTry(bson: BSONValue): Try[DateTime] =
      bson.asTry[BSONDateTime] map { dt =>
        new DateTime(dt.value)
      }
    @inline def writeTry(date: DateTime) = Success(BSONDateTime(date.getMillis))

  given UserIdHandler: BSONHandler[UserId] = BSONStringHandler.as(UserId.apply, _.value)

object Mongo extends MongoHandlers
