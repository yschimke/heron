/*
 *    Copyright 2024 Adetunji Dahunsi
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.tunjid.heron.ui.scaffold.navigation

import com.tunjid.heron.data.core.models.ModelUrlSafeBase64
import com.tunjid.treenav.push
import com.tunjid.treenav.strings.Route
import com.tunjid.treenav.strings.routeString
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** A single delivery, retained by the platform until it reaches the editable composer. */
data class TextShare(
    val id: String,
    val text: String,
) {
    companion object {
        fun create(
            id: String,
            text: String?,
            subject: String?,
        ): TextShare? {
            val body = text?.takeIf(String::isNotBlank)
            val title = subject?.takeIf(String::isNotBlank)
            val content = when {
                body == null -> title ?: return null
                title == null || body.startsWith(title) -> body
                else -> "$title\n$body"
            }
            return TextShare(id = id, text = content)
        }
    }
}

/** Cancellation (for example leaving sign-in) must leave the composer unopened. */
internal suspend fun deliverTextShare(
    share: TextShare,
    ready: Flow<Boolean>,
    signedIn: Flow<Boolean>,
    authenticationActive: Flow<Boolean>,
    requestSignIn: () -> Unit,
    openComposer: suspend (TextShare) -> Unit,
) {
    ready.first { it }
    if (!signedIn.first()) {
        requestSignIn()
        var enteredAuthentication = false
        combine(signedIn, authenticationActive) { authenticated, active ->
            if (active) enteredAuthentication = true
            authenticated || (enteredAuthentication && !active)
        }.first { it }
        // Re-read the repository after leaving auth: a successful login can navigate before
        // this collector sees its profile emission. Otherwise leaving auth cancels the share.
        if (!signedIn.first()) return
    }
    openComposer(share)
}

@OptIn(ExperimentalEncodingApi::class)
internal fun composeTextShare(
    share: TextShare,
): NavigationMutation = {
    val destination = routeString(
        path = "/compose",
        queryParams = mapOf(
            SharedTextQueryParam to listOf(ModelUrlSafeBase64.encode(share.text.encodeToByteArray())),
            "shareId" to listOf(share.id),
        ),
    ).toRoute
    val path = currentRoute.routeParams.pathAndQueries.substringBefore('?')
    val navigation = when {
        navState.stacks[navState.currentIndex].name == AppStack.Auth.stackName ||
            path == "/auth" || path.startsWith("/oauth/") -> NavigationState.SignedIn
        else -> navState
    }
    // Re-delivering a retained request must not replace an already edited composer.
    if (navigation.stacks.any { stack -> stack.children.any { it.id == destination.id } }) navigation
    else navigation.push(destination)
}

@OptIn(ExperimentalEncodingApi::class)
val Route.sharedText: String?
    get() = routeParams.queryParams[SharedTextQueryParam]?.firstOrNull()?.let { encoded ->
        runCatching { ModelUrlSafeBase64.decode(encoded).decodeToString() }.getOrNull()
    }

private const val SharedTextQueryParam = "sharedText"

/** Give each incoming share its own draft while keeping ordinary composer navigation unchanged. */
fun Route.withTextShareIdentity(): Route {
    val shareId = routeParams.queryParams["shareId"]?.firstOrNull() ?: return this
    return TextShareRoute(route = this, id = "$id?shareId=$shareId")
}

private data class TextShareRoute(
    private val route: Route,
    override val id: String,
) : Route by route
