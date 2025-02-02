package lila.ublog

import com.softwaremill.macwire._

import lila.common.config._

@Module
final class Env(
    db: lila.db.Db,
    userRepo: lila.user.UserRepo,
    timeline: lila.hub.actors.Timeline,
    picfit: lila.memo.PicfitApi
)(implicit
    ec: scala.concurrent.ExecutionContext
) {

  private val postColl = db(CollName("ublog_post"))

  val api = wire[UblogApi]

  val markup = wire[UblogMarkup]

  val form = wire[UblogForm]
}
