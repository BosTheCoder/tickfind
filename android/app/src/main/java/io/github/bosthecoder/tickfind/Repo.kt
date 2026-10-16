package io.github.bosthecoder.tickfind

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** The task index in memory, shared by the search box and settings, and the one background sync. */
object Repo {
    @Volatile private var index: Store.Index? = null
    @Volatile var syncing = false; private set
    @Volatile var error: String? = null
    /** --demo (an intent extra): made-up tasks, nothing reaches TickTick. */
    var demo = false
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()
    /** Every change to the index and every write of tasks.json goes through this lock and this one writer thread. */
    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor()

    fun index(ctx: Context): Store.Index = index ?: (if (demo) Store.Index(Demo.tasks(), mapOf(), System.currentTimeMillis())
        else Store(ctx).load()).also { i ->
            index = i
            thread { i.tasks.forEach { it.n } }   // fold every task once, off the main thread, so the first search is instant
        }

    fun startDemo() { demo = true; index = null }

    fun listen(f: () -> Unit) { listeners += f }
    fun unlisten(f: () -> Unit) { listeners -= f }
    private fun changed() = main.post { listeners.toList().forEach { it() } }

    /** Re-reads everything from TickTick in the background; listeners hear when it's done. */
    fun sync(ctx: Context) {
        val store = Store(ctx)
        if (demo || syncing || !store.signedIn) return
        syncing = true
        changed()
        thread {
            try {
                val r = TickTick(store).sync(store.archived)
                r.tasks.forEach { it.n }
                synchronized(lock) {
                    // A list that couldn't be read keeps its old tasks, and the index stays due for a sync, so nothing vanishes from search.
                    val old = index(ctx)
                    index = if (r.failed.isEmpty()) Store.Index(r.tasks, r.lists, System.currentTimeMillis())
                        else Store.Index(r.tasks + old.tasks.filter { it.list in r.failed }, r.lists, old.synced)
                }
                persist(ctx)
                error = if (r.failed.isEmpty()) null else "Couldn't read ${r.failed.joinToString()}"
            } catch (e: NeedsSignIn) {
                error = null
            } catch (e: Exception) {
                error = "Sync failed: ${e.message}"
            } finally {
                syncing = false
                changed()
            }
        }
    }

    fun stale(ctx: Context, minutes: Int = 5) = System.currentTimeMillis() - index(ctx).synced > minutes * 60_000L

    /** After a save or a completion, so the index matches TickTick without a full sync. */
    fun put(ctx: Context, t: TTask) = edit(ctx) { tasks -> tasks.map { if (it.id == t.id) t else it } }
    fun remove(ctx: Context, id: String) = edit(ctx) { tasks -> tasks.filter { it.id != id } }

    private fun edit(ctx: Context, f: (List<TTask>) -> List<TTask>) {
        synchronized(lock) { val i = index(ctx); index = i.copy(tasks = f(i.tasks)) }
        persist(ctx)
    }

    /** Writes whatever the index is by the time the writer gets to it, one write at a time. */
    private fun persist(ctx: Context) {
        if (!demo) writer.execute { synchronized(lock) { index }?.let { Store(ctx).save(it) } }
    }

    fun clear(ctx: Context) { index = null; Store(ctx).clear() }
}
