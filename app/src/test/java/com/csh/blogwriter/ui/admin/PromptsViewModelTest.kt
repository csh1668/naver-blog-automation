package com.csh.blogwriter.ui.admin

import com.csh.blogwriter.chat.PromptSection
import com.csh.blogwriter.chat.PromptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
class PromptsViewModelTest {
    private val overrides = MutableStateFlow<Map<PromptSection, String>>(emptyMap())
    private val store = object : PromptStore {
        override suspend fun text(section: PromptSection) = overrides.value[section] ?: "기본 ${section.name}"
        override fun observe(section: PromptSection): Flow<String> = overrides.map { it[section] ?: "기본 ${section.name}" }
        override suspend fun override(section: PromptSection, text: String?) { overrides.value = if (text == null) overrides.value - section else overrides.value + (section to text) }
        override suspend fun isOverridden(section: PromptSection) = section in overrides.value
    }
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun saveAndResetReflectInState() = runTest {
        val vm = PromptsViewModel(store); advanceUntilIdle()
        assertEquals(PromptSection.entries.size, vm.sections.value.size)
        vm.save(PromptSection.ROLE, "내 역할"); advanceUntilIdle()
        val role = vm.sections.value.first { it.section == PromptSection.ROLE }
        assertEquals("내 역할", role.text); assertEquals(true, role.overridden)
        vm.reset(PromptSection.ROLE); advanceUntilIdle()
        assertEquals("기본 ROLE", vm.sections.value.first { it.section == PromptSection.ROLE }.text)
    }
}
