/*
 * (C) Copyright 2015-2019 Nuxeo (http://nuxeo.com/) and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Delbosc Benoit
 */
package org.nuxeo.cap.bench

import io.gatling.core.Predef._
import io.gatling.core.config.GatlingFiles
import io.gatling.http.Predef._

import scala.io.Source

class Sim90Cleanup extends Simulation {
  def run = (userCount: Integer) => {
    feed(Feeders.admins)
      .exec(NuxeoRest.permanentlyDelete(Constants.GAT_WS_PATH))
      .exec(NuxeoRest.waitForAsyncJobs())
      .repeat(userCount.intValue(), "count") {
      feed(Feeders.users)
        .exec(NuxeoRest.deleteUser())
    }.exec(NuxeoRest.deleteGroup(Constants.GAT_GROUP_NAME))
  }

  val url = System.getProperty("url", "http://localhost:8080/nuxeo")
  val httpProtocol = http
    .baseUrl(url)
    .disableWarmUp
    .acceptEncodingHeader("gzip, deflate")
    .connectionHeader("keep-alive")
  val userCount = Source.fromFile(GatlingFiles.resourcesDirectory + "/data/users.csv").getLines.size - 1
  val scn = scenario("Cleanup").exec(run(userCount))
  setUp(scn.inject(atOnceUsers(1))).protocols(httpProtocol)
}
