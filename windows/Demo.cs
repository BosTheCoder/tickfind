namespace Tickfind;

/// Made-up tasks for --demo: screenshots and trying it without a TickTick account.
static class Demo
{
    public static List<TTask> Tasks()
    {
        var now = DateTimeOffset.Now;
        var n = 0;
        TTask T(string title, int pri, string list, string content = "", string[]? tags = null, string[]? items = null,
            int? dueDays = null, string kind = "TEXT", int ageDays = 1) =>
            new($"demo{++n}", "demo", title, content, kind, pri, tags ?? [], items ?? [],
                dueDays is int d ? new DateTimeOffset(now.Date.AddDays(d)) : null, now.AddDays(-ageDays), now.AddDays(-ageDays * 3), list);
        return
        [
            T("Glance: snooze button on the alert page", 3, "👨🏿‍💻App ideas", "Make Snooze the big button and move Skip into the menu.", ["glance"], ageDays: 2),
            T("Plumber quote for the kitchen tap", 5, "🧹Household", "Two quotes so far: £120 and £95. Ask about the isolation valve.", ["home"], dueDays: 1),
            T("Book flights to Nairobi for December", 5, "✈️Travelling", "Watch the Kenya fare tracker. Check baggage on the cheaper one.", ["travel"], dueDays: 5),
            T("Ideas for the church youth day", 3, "✝Christianity", "Games, a short talk on Daniel 1, lunch: pasta and salmon.", items: ["Ask Pastor about the hall", "Budget for food"], kind: "CHECKLIST"),
            T("Tickfind: semantic search later", 1, "👨🏿‍💻App ideas", "Embeddings over every task, so 'that plumber thing' finds the tap quote."),
            T("Read: Atomic Habits", 0, "📔To Read", "Chapter 3 on identity-based habits."),
            T("Gym: new leg day routine", 3, "👨🏾‍⚕Health", "Split squats, RDLs, hamstring curls. Keep the knee happy.", ["gym"], dueDays: 0, ageDays: 4),
            T("Call the bank about the new card", 3, "✔Personal", "It hasn't arrived. Ask them to cancel the old one.", ["today"], ageDays: 0),
            T("Renew passport", 5, "✔Personal", "Photo booth at the post office. Old passport expires March.", dueDays: -2),
            T("Amapiano night with the crew", 1, "💃Socialising", "Find a Saturday that works. Ask in the group chat."),
            T("Quotes I liked", 0, "🗣Quotes", "\"The best code is the code never written.\"", kind: "NOTE", ageDays: 20),
            T("Weekly review", 3, "🔁Recurring", "Clear the inbox, check the goals list, plan the week.", dueDays: 2),
            T("Glance: Android widget polish", 1, "👨🏿‍💻App ideas", "Rounded corners on the side strip.", ["glance"], ageDays: 9),
        ];
    }
}
