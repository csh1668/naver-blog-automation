package com.csh.blogwriter.ui.memory

import app.cash.turbine.test
import com.csh.blogwriter.data.repo.MemoryItem
import com.csh.blogwriter.data.repo.MemoryKind
import com.csh.blogwriter.data.repo.MemoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryViewModelTest {
    private val items = MutableStateFlow<List<MemoryItem>>(emptyList())
    private val repo = object : MemoryRepository {
        override fun observeAll() = items
        override suspend fun activeItems(limit: Int) = items.value.filter { it.enabled }.take(limit)
        override suspend fun add(kind: MemoryKind, text: String, source: String): MemoryItem { val m = MemoryItem((items.value.size + 1).toLong(), kind, text, source, 0, true, null); items.value = items.value + m; return m }
        override suspend fun update(id: Long, text: String) { items.value = items.value.map { if (it.id == id) it.copy(text = text) else it } }
        override suspend fun setEnabled(id: Long, enabled: Boolean) { items.value = items.value.map { if (it.id == id) it.copy(enabled = enabled) else it } }
        override suspend fun delete(id: Long) { items.value = items.value.filterNot { it.id == id } }
        override suspend fun touch(ids: List<Long>) {}
    }
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun addEditToggleDelete() = runTest {
        val vm = MemoryViewModel(repo)
        vm.items.test {
            assertEquals(0, awaitItem().size)
            vm.add("가격은 정확히 적기"); advanceUntilIdle()
            assertEquals(MemoryKind.PREFERENCE, awaitItem()[0].kind)
            vm.edit(1, "가격은 원 단위까지"); advanceUntilIdle()
            assertEquals("가격은 원 단위까지", awaitItem()[0].text)
            vm.toggle(1, false); advanceUntilIdle()
            assertEquals(false, awaitItem()[0].enabled)
            vm.delete(1); advanceUntilIdle()
            assertEquals(0, awaitItem().size)
        }
    }

    @Test
    fun editWithBlankTextLeavesItemUnchanged() = runTest {
        val vm = MemoryViewModel(repo)
        vm.items.test {
            assertEquals(0, awaitItem().size)
            vm.add("가격은 정확히 적기"); advanceUntilIdle()
            val added = awaitItem()[0]
            vm.edit(added.id, "  "); advanceUntilIdle()
            expectNoEvents()
            assertEquals("가격은 정확히 적기", vm.items.value[0].text)
        }
    }
}
