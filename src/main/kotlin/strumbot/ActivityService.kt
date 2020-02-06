/*
 * Copyright 2019-2020 Florian Spieß and the Strumbot Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package strumbot

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Activity
import reactor.core.publisher.Flux
import reactor.core.scheduler.Scheduler
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

class ActivityService(private val jda: JDA, private val pool: Scheduler) {
    private val activities = CopyOnWriteArrayList<Activity>()
    private var currentIndex = 0

    fun addActivity(activity: Activity) {
        activities.add(activity)
    }

    fun removeActivity(activity: Activity) {
        activities.remove(activity)
    }

    fun start() {
        Flux.interval(Duration.ofSeconds(5), Duration.ofSeconds(15), pool)
            .map { activities }
            .subscribe {
                if (it.isEmpty()) {
                    if (jda.presence.activity != null)
                        jda.presence.activity = null
                } else {
                    activities[currentIndex++ % it.size].let { activity ->
                        if (jda.presence.activity != activity)
                            jda.presence.activity = activity
                    }
                }
            }
    }
}