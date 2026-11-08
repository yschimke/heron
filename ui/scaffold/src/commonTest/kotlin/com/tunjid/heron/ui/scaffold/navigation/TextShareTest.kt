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

import com.tunjid.treenav.MultiStackNav
import com.tunjid.treenav.current
import com.tunjid.treenav.push
import com.tunjid.treenav.strings.Route
import com.tunjid.treenav.strings.routeOf
import com.tunjid.treenav.strings.routeParserFrom
import com.tunjid.treenav.strings.urlRouteMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class TextShareTest {
    private val share = TextShare(
        id = "delivery-1",
        text = "Try this 👋\nhttps://preview.coo.ee/p/one?theme=Dark&name=A+B#sample",
    )

    private val parser = routeParserFrom(
        *listOf("/compose", "/auth", "/oauth/callback", "/home").map { path ->
            urlRouteMatcher(
                routePattern = path,
                routeMapper = { params ->
                    routeOf(params = params).let { route ->
                        if (path == "/compose") route.withTextShareIdentity() else route
                    }
                },
            )
        }.toTypedArray(),
    )

    @Test
    fun sharedTextRoundTripsWithoutChangingUnicodeOrUrlCharacters() {
        val result = open(share = share, navigation = NavigationState.SignedIn)
        assertEquals(share.text, (result.current as Route).sharedText)
    }

    @Test
    fun longSharedTextRemainsEditableInsteadOfBeingSilentlyTruncated() {
        val longShare = share.copy(text = "Long shared text 👋 ".repeat(100))
        val result = open(share = longShare, navigation = NavigationState.SignedIn)
        assertEquals(longShare.text, (result.current as Route).sharedText)
    }

    @Test
    fun sameDeliveryDoesNotReplaceAnExistingComposerButNewDeliveryCan() {
        val first = open(share = share, navigation = NavigationState.SignedIn)
        assertEquals(first, open(share = share, navigation = first))
        val second = open(share = share.copy(id = "delivery-2"), navigation = first)
        assertEquals(first.stacks[0].children.size + 1, second.stacks[0].children.size)
        // Restoration parses the stored route again; it must recover the same draft identity.
        val current = second.current as Route
        val restored = requireNotNull(parser.parse(current.routeParams.pathAndQueries))
        assertEquals(current.id, restored.id)
    }

    @Test
    fun completedSignInOpensComposerOnHomeInsteadOfLeavingAuthUnderneath() {
        val oauth = NavigationState.SignedOut.push(routeOf(path = "/oauth/callback?code=test"))
        val result = open(share = share, navigation = oauth)
        assertEquals(AppStack.Home.stackName, result.stacks[0].name)
        assertEquals(AppStack.Home.rootRoute, result.stacks[0].children.first())
        assertEquals(share.text, (result.current as Route).sharedText)
    }

    @Test
    fun textWaitsForNavigationRestoreAndSignIn() = runTest {
        val ready = MutableStateFlow(false)
        val signedIn = MutableStateFlow(false)
        var requests = 0
        val opened = mutableListOf<TextShare>()
        val job = launch {
            deliverTextShare(
                share = share,
                ready = ready,
                signedIn = signedIn,
                authenticationActive = MutableStateFlow(true),
                requestSignIn = { requests++ },
                openComposer = opened::add,
            )
        }
        runCurrent()
        assertEquals(0, requests)
        ready.value = true
        runCurrent()
        assertEquals(1, requests)
        assertEquals(emptyList(), opened)
        signedIn.value = true
        job.join()
        assertEquals(listOf(share), opened)
    }

    @Test
    fun signedInShareDoesNotRequestAuthentication() = runTest {
        val opened = mutableListOf<TextShare>()
        deliverTextShare(
            share = share,
            ready = MutableStateFlow(true),
            signedIn = MutableStateFlow(true),
            authenticationActive = MutableStateFlow(false),
            requestSignIn = { error("Already signed in") },
            openComposer = opened::add,
        )
        assertEquals(listOf(share), opened)
    }

    @Test
    fun cancellingSignInNeverOpensThePendingShareLater() = runTest {
        val signedIn = MutableStateFlow(false)
        val opened = mutableListOf<TextShare>()
        val job = launch {
            deliverTextShare(
                share = share,
                ready = MutableStateFlow(true),
                signedIn = signedIn,
                authenticationActive = MutableStateFlow(true),
                requestSignIn = {},
                openComposer = opened::add,
            )
        }
        runCurrent()
        job.cancel()
        signedIn.value = true
        job.join()
        assertEquals(emptyList(), opened)
    }

    @Test
    fun replacementWhileSigningInOnlyDeliversTheLatestRequest() = runTest {
        val signedIn = MutableStateFlow(false)
        val opened = mutableListOf<TextShare>()
        fun deliver(incoming: TextShare) = launch {
            deliverTextShare(
                share = incoming,
                ready = MutableStateFlow(true),
                signedIn = signedIn,
                authenticationActive = MutableStateFlow(true),
                requestSignIn = {},
                openComposer = opened::add,
            )
        }
        val old = deliver(incoming = share)
        runCurrent()
        old.cancel()
        val latest = share.copy(id = "delivery-2", text = "Another link")
        val next = deliver(incoming = latest)
        runCurrent()
        signedIn.value = true
        next.join()
        assertEquals(listOf(latest), opened)
    }

    @Test
    fun leavingAuthenticationCancelsEvenWithoutAnActivityBackCallback() = runTest {
        val signedIn = MutableStateFlow(false)
        val authenticationActive = MutableStateFlow(false)
        val opened = mutableListOf<TextShare>()
        val job = launch {
            deliverTextShare(
                share = share,
                ready = MutableStateFlow(true),
                signedIn = signedIn,
                authenticationActive = authenticationActive,
                requestSignIn = { authenticationActive.value = true },
                openComposer = opened::add,
            )
        }
        runCurrent()
        authenticationActive.value = false
        job.join()
        signedIn.value = true
        runCurrent()
        assertEquals(emptyList(), opened)
    }

    @Test
    fun malformedEncodedSharedTextIsIgnored() {
        val route = routeOf(
            path = "/compose",
            queryParams = mapOf("sharedText" to listOf("not valid base64!")),
        )
        assertNull(route.sharedText)
    }

    @Test
    fun subjectAndBodyAreCombinedWithoutDuplicatingAnExistingTitle() {
        assertEquals(
            "Title\nhttps://example.com",
            TextShare.create(id = "1", text = "https://example.com", subject = "Title")?.text,
        )
        assertEquals(
            "Title\nhttps://example.com",
            TextShare.create(id = "1", text = "Title\nhttps://example.com", subject = "Title")?.text,
        )
        assertEquals("Title", TextShare.create(id = "1", text = null, subject = "Title")?.text)
        assertNull(TextShare.create(id = "1", text = "  ", subject = null))
    }

    private fun open(
        share: TextShare,
        navigation: MultiStackNav,
    ) = composeTextShare(share = share).invoke(
        ImmutableNavigationContext(state = navigation, routeParser = parser),
    )
}
