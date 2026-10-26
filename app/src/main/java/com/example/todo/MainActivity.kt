package com.example.todo

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.todo.databinding.ActivityMainBinding
import com.example.todo.databinding.DialogEditBinding
import com.google.android.material.snackbar.Snackbar
import java.util.Calendar
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private enum class Filter { ALL, ACTIVE, DONE }

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: TaskStore
    private lateinit var adapter: TaskAdapter
    private var tasks = mutableListOf<Task>()
    private var filter = Filter.ALL
    private var pendingDue: Long? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = TaskStore(this)
        tasks = store.load()
        // 예약이 유실됐을 수 있으니 시작할 때마다 다시 맞춘다.
        Reminders.syncAll(this, tasks)

        adapter = TaskAdapter(
            onToggle = { t -> update(t.copy(done = !t.done)) },
            onEdit = ::showEditDialog,
            onDelete = ::delete,
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        attachSwipeToDelete()

        binding.dueButton.setOnClickListener {
            pickDateTime(pendingDue) { pendingDue = it; renderPendingDue() }
        }
        binding.clearDueButton.setOnClickListener {
            pendingDue = null
            renderPendingDue()
        }
        binding.addButton.setOnClickListener { addFromInput() }
        binding.aiButton.setOnClickListener { aiAddFromInput() }
        binding.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addFromInput()
                true
            } else {
                false
            }
        }

        binding.filterGroup.setOnCheckedStateChangeListener { _, ids ->
            filter = when (ids.firstOrNull()) {
                R.id.chipActive -> Filter.ACTIVE
                R.id.chipDone -> Filter.DONE
                else -> Filter.ALL
            }
            render()
        }

        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_clear_done -> {
                    tasks.filter { t -> t.done }.forEach { t -> Reminders.cancel(this, t) }
                    tasks.removeAll { t -> t.done }
                    persistAndRender()
                    true
                }
                R.id.action_ai_priority -> {
                    aiPrioritize()
                    true
                }
                R.id.action_ai_settings -> {
                    showKeyDialog()
                    true
                }
                else -> false
            }
        }

        render()
    }

    private fun addFromInput() {
        val text = binding.input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        // 새 항목이 가장 위에 보이도록 앞에 추가한다.
        val task = Task(id = System.currentTimeMillis(), title = text, dueAt = pendingDue)
        tasks.add(0, task)
        Reminders.sync(this, task)
        binding.input.text?.clear()
        pendingDue = null
        renderPendingDue()
        persistAndRender()
        binding.list.scrollToPosition(0)
    }

    private fun update(task: Task) {
        val i = tasks.indexOfFirst { it.id == task.id }
        if (i >= 0) {
            tasks[i] = task
            Reminders.sync(this, task)
            persistAndRender()
        }
    }

    private fun delete(task: Task) {
        val index = tasks.indexOfFirst { it.id == task.id }
        if (index < 0) return
        tasks.removeAt(index)
        Reminders.cancel(this, task)
        persistAndRender()
        Snackbar.make(binding.root, R.string.deleted, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                tasks.add(index.coerceAtMost(tasks.size), task)
                Reminders.sync(this, task)
                persistAndRender()
            }
            .show()
    }

    private fun showEditDialog(task: Task) {
        val dialogBinding = DialogEditBinding.inflate(layoutInflater)
        val input: EditText = dialogBinding.editInput
        input.setText(task.title)
        input.setSelection(input.text.length)
        var due = task.dueAt
        fun renderDue() {
            dialogBinding.dueButton.text =
                due?.let { getString(R.string.due_prefix, Reminders.formatDue(it)) } ?: getString(R.string.set_due)
            dialogBinding.clearDueButton.visibility = if (due == null) View.GONE else View.VISIBLE
        }
        renderDue()
        dialogBinding.dueButton.setOnClickListener { pickDateTime(due) { due = it; renderDue() } }
        dialogBinding.clearDueButton.setOnClickListener { due = null; renderDue() }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.edit_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) update(task.copy(title = text, dueAt = due))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialogBinding.splitButton.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            dialog.dismiss()
            aiSplit(task.id, text, due)
        }
    }

    // ---- AI 일정관리 ----

    /** 키가 없으면 설정 창을 띄우고, 있으면 백그라운드에서 [work]를 실행한 뒤 UI 스레드에서 [onDone]을 호출한다. */
    private fun <T> runAi(work: (String) -> T, onDone: (T) -> Unit) {
        val key = AiSettings(this).apiKey
        if (key == null) {
            Toast.makeText(this, R.string.ai_key_needed, Toast.LENGTH_SHORT).show()
            showKeyDialog()
            return
        }
        val bar = Snackbar.make(binding.root, R.string.ai_working, Snackbar.LENGTH_INDEFINITE)
        bar.show()
        binding.aiButton.isEnabled = false
        thread {
            val result = try {
                Result.success(work(key))
            } catch (e: AiException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(AiException("예상치 못한 오류: ${e.message}"))
            }
            runOnUiThread {
                bar.dismiss()
                binding.aiButton.isEnabled = true
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess(onDone).onFailure {
                    Snackbar.make(binding.root, it.message ?: "AI 오류", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showKeyDialog() {
        val settings = AiSettings(this)
        val field = EditText(this).apply {
            hint = getString(R.string.ai_key_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val frame = android.widget.FrameLayout(this).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, 8, pad, 0)
            addView(field)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.ai_key_title)
            .setMessage(R.string.ai_key_message)
            .setView(frame)
            .setPositiveButton(R.string.save) { _, _ ->
                val key = field.text.toString().trim()
                if (key.isNotEmpty()) settings.apiKey = key
            }
            .setNegativeButton(R.string.cancel, null)
        if (settings.apiKey != null) {
            builder.setNeutralButton(R.string.remove_key) { _, _ -> settings.apiKey = null }
        }
        builder.show()
    }

    /** 입력창의 자연어 문장을 AI가 할 일(들)로 바꿔 등록한다. */
    private fun aiAddFromInput() {
        val text = binding.input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.ai_no_input, Toast.LENGTH_SHORT).show()
            return
        }
        runAi({ key -> AiAssistant.parseTasks(key, text) }) { drafts ->
            if (drafts.isEmpty()) {
                Snackbar.make(binding.root, R.string.ai_nothing, Snackbar.LENGTH_LONG).show()
            } else {
                binding.input.text?.clear()
                addDrafts(drafts, 0)
                binding.list.scrollToPosition(0)
            }
        }
    }

    private fun addDrafts(drafts: List<DraftTask>, at: Int) {
        val base = System.currentTimeMillis()
        // 같은 밀리초에 만든 항목끼리 id가 겹치지 않도록 순번을 더한다.
        val created = drafts.mapIndexed { i, d -> Task(id = base + i, title = d.title, dueAt = d.dueAt) }
        tasks.addAll(at.coerceIn(0, tasks.size), created)
        created.forEach { Reminders.sync(this, it) }
        if (created.any { it.dueAt != null }) requestNotificationPermissionIfNeeded()
        persistAndRender()
        Snackbar.make(binding.root, getString(R.string.ai_added, created.size), Snackbar.LENGTH_SHORT).show()
    }

    private fun aiPrioritize() {
        val active = tasks.filter { !it.done }
        if (active.isEmpty()) {
            Toast.makeText(this, R.string.ai_no_active, Toast.LENGTH_SHORT).show()
            return
        }
        runAi({ key -> AiAssistant.prioritize(key, active) }) { result ->
            val byId = tasks.associateBy { it.id }
            val lines = result.items.mapIndexedNotNull { i, item ->
                byId[item.id]?.let { "${i + 1}. ${it.title}\n    ${item.reason}" }
            }
            if (lines.isEmpty()) {
                Snackbar.make(binding.root, R.string.ai_nothing, Snackbar.LENGTH_LONG).show()
                return@runAi
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.ai_priority)
                .setMessage(result.summary + "\n\n" + lines.joinToString("\n\n"))
                .setPositiveButton(R.string.ai_apply_order) { _, _ ->
                    val ordered = result.items.mapNotNull { item -> tasks.firstOrNull { it.id == item.id } }
                    val ids = ordered.map { it.id }.toSet()
                    tasks = (ordered + tasks.filter { it.id !in ids }).toMutableList()
                    persistAndRender()
                    binding.list.scrollToPosition(0)
                }
                .setNegativeButton(R.string.close, null)
                .show()
        }
    }

    /** 큰 일을 세부 작업으로 나눈 제안을 보여주고, 확인하면 원래 항목 바로 아래에 추가한다. */
    private fun aiSplit(taskId: Long, title: String, dueAt: Long?) {
        runAi({ key -> AiAssistant.decompose(key, title, dueAt) }) { drafts ->
            if (drafts.isEmpty()) {
                Snackbar.make(binding.root, R.string.ai_nothing, Snackbar.LENGTH_LONG).show()
                return@runAi
            }
            val preview = drafts.mapIndexed { i, d ->
                "${i + 1}. ${d.title}" + (d.dueAt?.let { "\n    " + getString(R.string.due_prefix, Reminders.formatDue(it)) } ?: "")
            }.joinToString("\n\n")
            AlertDialog.Builder(this)
                .setTitle(R.string.ai_split_title)
                .setMessage(preview)
                .setPositiveButton(R.string.ai_add_all) { _, _ ->
                    val idx = tasks.indexOfFirst { it.id == taskId }
                    addDrafts(drafts, if (idx >= 0) idx + 1 else 0)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** 날짜 → 시간 순서로 고르고, 알림 권한(Android 13+)이 없으면 함께 요청한다. */
    private fun pickDateTime(initial: Long?, onPicked: (Long) -> Unit) {
        val cal = Calendar.getInstance().apply {
            timeInMillis = initial ?: (System.currentTimeMillis() + 60 * 60 * 1000)
        }
        DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, h, min ->
                cal.set(y, m, d, h, min, 0)
                cal.set(Calendar.MILLISECOND, 0)
                onPicked(cal.timeInMillis)
                requestNotificationPermissionIfNeeded()
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun renderPendingDue() {
        val due = pendingDue
        binding.dueButton.text =
            due?.let { getString(R.string.due_prefix, Reminders.formatDue(it)) } ?: getString(R.string.set_due)
        binding.clearDueButton.visibility = if (due == null) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        // 시간이 지나 기한 초과 표시가 바뀌었을 수 있으니 다시 그린다.
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    private fun attachSwipeToDelete() {
        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) delete(adapter.currentList[pos])
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.list)
    }

    private fun persistAndRender() {
        store.save(tasks)
        render()
    }

    private fun render() {
        val visible = when (filter) {
            Filter.ALL -> tasks
            Filter.ACTIVE -> tasks.filter { !it.done }
            Filter.DONE -> tasks.filter { it.done }
        }
        adapter.submitList(visible.toList())
        binding.empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        binding.summary.text = getString(R.string.summary, tasks.count { !it.done }, tasks.size)
    }
}
