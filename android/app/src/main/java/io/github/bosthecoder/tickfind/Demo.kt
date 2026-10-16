package io.github.bosthecoder.tickfind

import java.time.LocalDate
import java.time.ZoneId

/** Made-up tasks for demo mode (screenshots, trying it without TickTick). The same list as the Windows app's Demo.cs. */
object Demo {
    fun tasks(): List<TTask> {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        var n = 0
        fun t(title: String, pri: Int, list: String, content: String = "", tags: List<String> = listOf(), items: List<String> = listOf(),
              dueDays: Int? = null, kind: String = "TEXT", ageDays: Int = 1) =
            TTask("demo${++n}", "demo", title, content, kind, pri, tags, items,
                dueDays?.let { LocalDate.now().plusDays(it.toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() },
                now - ageDays * day, now - ageDays * 3 * day, list)
        return listOf(
            t("Glance: snooze button on the alert page", 3, "👨🏿‍💻App ideas", "Make Snooze the big button and move Skip into the menu.", listOf("glance"), ageDays = 2),
            t("Plumber quote for the kitchen tap", 5, "🧹Household", "Two quotes so far: £120 and £95. Ask about the isolation valve.", listOf("home"), dueDays = 1),
            t("Book flights to Nairobi for December", 5, "✈️Travelling", "Watch the Kenya fare tracker. Check baggage on the cheaper one.", listOf("travel"), dueDays = 5),
            t("Ideas for the church youth day", 3, "✝Christianity", "Games, a short talk on Daniel 1, lunch: pasta and salmon.", items = listOf("Ask Pastor about the hall", "Budget for food"), kind = "CHECKLIST"),
            t("Tickfind: semantic search later", 1, "👨🏿‍💻App ideas", "Embeddings over every task, so 'that plumber thing' finds the tap quote."),
            t("Read: Atomic Habits", 0, "📔To Read", "Chapter 3 on identity-based habits."),
            t("Gym: new leg day routine", 3, "👨🏾‍⚕Health", "Split squats, RDLs, hamstring curls. Keep the knee happy.", listOf("gym"), ageDays = 4),
            t("Renew passport", 5, "✔Personal", "Photo booth at the post office. Old passport expires March.", dueDays = -2),
            t("Amapiano night with the crew", 1, "💃Socialising", "Find a Saturday that works. Ask in the group chat."),
            t("Quotes I liked", 0, "🗣Quotes", "\"The best code is the code never written.\"", kind = "NOTE", ageDays = 20),
            t("Weekly review", 3, "🔁Recurring", "Clear the inbox, check the goals list, plan the week.", dueDays = 2),
            t("Glance: Android widget polish", 1, "👨🏿‍💻App ideas", "Rounded corners on the side strip.", listOf("glance"), ageDays = 9),
        )
    }
}
