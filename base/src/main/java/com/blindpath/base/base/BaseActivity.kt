package com.blindpath.base.base

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Activity基类
 * 提供ViewBinding、生命周期观察等通用功能
 */
abstract class BaseActivity<VB : ViewBinding> : AppCompatActivity() {

    private var _binding: VB? = null
    protected val binding: VB get() = _binding!!

    protected abstract fun createBinding(): VB
    protected abstract fun setupViews()
    protected abstract fun observeState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = createBinding()
        setContentView(binding.root)
        setupViews()
        observeState()
        Timber.d("${javaClass.simpleName} created")
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
        Timber.d("${javaClass.simpleName} destroyed")
    }

    /**
     * 收集Flow数据（Lifecycle感知）
     */
    protected fun <T> Flow<T>.collectOnLifecycle(
        state: Lifecycle.State = Lifecycle.State.STARTED,
        action: suspend (T) -> Unit
    ) {
        lifecycleScope.launch {
            repeatOnLifecycle(state) {
                collectLatest { action(it) }
            }
        }
    }
}
