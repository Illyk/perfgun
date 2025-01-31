/*
 * Copyright 2011-2022 GatlingCorp (https://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gatling.core.stats.writer

import java.nio.charset.CodingErrorAction
import java.util.Date

import scala.collection.mutable
import scala.io.Codec

import io.gatling.commons.stats.{ KO, OK }
import io.gatling.commons.util.Clock
import io.gatling.core.config.GatlingConfiguration

private[gatling] final class UserCounters(val totalUserCount: Option[Long]) {

  private var _activeCount: Long = 0
  private var _doneCount: Long = 0

  def activeCount: Long = _activeCount

  def doneCount: Long = _doneCount

  def userStart(): Unit = _activeCount += 1

  def userDone(): Unit = {
    _activeCount -= 1
    _doneCount += 1
  }

  def waitingCount: Long = totalUserCount.map(c => math.max(c - _activeCount - _doneCount, 0)).getOrElse(0L)
}

private object RequestCounters {
  def empty: RequestCounters = new RequestCounters(0, 0)
}

private[gatling] final class RequestCounters(var successfulCount: Int, var failedCount: Int)

private[gatling] final class ConsoleData(val startUpTime: Long) extends DataWriterData {
  var complete: Boolean = false
  val usersCounters: mutable.Map[String, UserCounters] = mutable.Map.empty
  val globalRequestCounters: RequestCounters = RequestCounters.empty
  val requestsCounters: mutable.Map[String, RequestCounters] = mutable.LinkedHashMap.empty
  val errorsCounters: mutable.Map[String, Int] = mutable.LinkedHashMap.empty
}

private[gatling] final class ConsoleDataWriter(clock: Clock, configuration: GatlingConfiguration) extends DataWriter[ConsoleData] {

  private val flushTimerName = "flushTimer"

  def onInit(init: Init): ConsoleData = {

    import init._

    val data = new ConsoleData(clock.nowMillis)

    scenarios.foreach(scenario => data.usersCounters.put(scenario.name, new UserCounters(scenario.totalUserCount)))

    startTimerAtFixedRate(flushTimerName, Flush, configuration.data.console.writePeriod)

    data
  }

  override def onFlush(data: ConsoleData): Unit = {
    import data._

    val runDuration = (clock.nowMillis - startUpTime) / 1000

    val summary = ConsoleSummary(runDuration, usersCounters, globalRequestCounters, requestsCounters, errorsCounters, configuration, new Date)
    complete = summary.complete
    println(summary.text)
  }

  override def onMessage(message: LoadEventMessage, data: ConsoleData): Unit = message match {
    case user: UserStartMessage    => onUserStartMessage(user, data)
    case user: UserEndMessage      => onUserEndMessage(user, data)
    case response: ResponseMessage => onResponseMessage(response, data)
    case error: ErrorMessage       => onErrorMessage(error, data)
    case _                         =>
  }

  private def onUserStartMessage(user: UserStartMessage, data: ConsoleData): Unit = {
    import data._
    import user._

    usersCounters.get(scenario) match {
      case Some(userCounters) => userCounters.userStart()
      case _                  => logger.error(s"Internal error, scenario '$scenario' has not been correctly initialized")
    }
  }

  private def onUserEndMessage(user: UserEndMessage, data: ConsoleData): Unit = {
    import data._
    import user._

    usersCounters.get(scenario) match {
      case Some(userCounters) => userCounters.userDone()
      case _                  => logger.error(s"Internal error, scenario '$scenario' has not been correctly initialized")
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.ListAppend"))
  private def onResponseMessage(response: ResponseMessage, data: ConsoleData): Unit = {
    import data._
    import response._

    val requestPath = (groupHierarchy :+ name).mkString(" / ")
    val requestCounters = requestsCounters.getOrElseUpdate(requestPath, RequestCounters.empty)

    status match {
      case OK =>
        globalRequestCounters.successfulCount += 1
        requestCounters.successfulCount += 1
      case KO =>
        globalRequestCounters.failedCount += 1
        requestCounters.failedCount += 1
        val errorMessage = message.getOrElse("<no-message>")
        errorsCounters(errorMessage) = errorsCounters.getOrElse(errorMessage, 0) + 1
    }

    val time = System.currentTimeMillis()
    val duration = (clock.nowMillis - startUpTime) / 1000
    val build_id = getProperty("build_id")
    //    val build_id = System.getProperty("build_id", "Empty")
    val lg_id = getProperty("lg_id")
    //    val lg_id = System.getProperty("lg_id", "Empty")
    val simulation_name = getProperty("simulation_name")
    //    val simulation_name = System.getProperty("simulation_name", "Empty")
    val test_type = getProperty("test_type")
    //    val test_type = System.getProperty("test_type", "Empty")
    val env = getProperty("env")
    //    val env = System.getProperty("env", "Empty")
    var users_info = s"""$time\tusers\t"""
    for ((k, v) <- usersCounters) {
      users_info += v.activeCount
      users_info += "\t" + v.waitingCount
      users_info += "\t" + v.doneCount
      users_info += "\t" + v.totalUserCount.get
    }
    users_info += s"""\t$env\t$test_type\t$build_id\t$lg_id\t$simulation_name\t"""
    logger.debug(users_info)
  }

  private def onErrorMessage(error: ErrorMessage, data: ConsoleData): Unit = {
    import data._
    errorsCounters(error.message) = errorsCounters.getOrElse(error.message, 0) + 1
  }

  override def onCrash(cause: String, data: ConsoleData): Unit = {}

  override def onStop(data: ConsoleData): Unit = {
    cancelTimer(flushTimerName)
    if (!data.complete) onFlush(data)
  }

  def getParameterFromFile(propertyName: String): String = {
    val decoder = Codec.UTF8.decoder.onMalformedInput(CodingErrorAction.IGNORE)
    val source = scala.io.Source.fromFile("TEST_PARAMS.txt")(decoder)
    val lines =
      try source.mkString
      catch {
        case _: Exception => ""
      } finally source.close
    val prop = lines.split("-D|,").filter(_.contains(propertyName + "="))
    if (prop.length == 0) "" else prop.map(_.split("=")).head(1)
  }

  def getProperty(propertyName: String): String = {
    var propertyValue = System.getProperty(propertyName, "")
    if (propertyValue.isEmpty) propertyValue = System.getenv(propertyName)
    if (propertyValue == null) propertyValue = getParameterFromFile(propertyName)
    propertyValue
  }
}
