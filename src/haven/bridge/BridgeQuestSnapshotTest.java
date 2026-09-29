package haven.bridge;

import haven.Coord;
import haven.Loading;
import haven.QuestWnd;
import org.json.JSONArray;
import org.json.JSONObject;

/** Snapshot checks against real quest widgets, without a game connection. */
public final class BridgeQuestSnapshotTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        System.setProperty("haven.uiscale", "1");
        System.setProperty("java.awt.headless", "true");
        check(!BridgeSnapshot.quests((QuestWnd)null).getBoolean("available"), "missing quest widget is unavailable");
        QuestWnd window = new QuestWnd();
        JSONObject empty = BridgeSnapshot.quests(window);
        check(empty.getBoolean("available") && empty.getJSONArray("quests").length() == 0,
            "loaded empty quest list is available");
        check(empty.isNull("selected_quest_id"), "no selected quest is explicit");

        QuestWnd.Quest current = quest(10, "A new beginning", QuestWnd.Quest.QST_PEND, 2, 1);
        QuestWnd.Quest loading = quest(20, null, QuestWnd.Quest.QST_DISABLED, 1, 0);
        QuestWnd.Quest completed = quest(30, "Finished quest", QuestWnd.Quest.QST_DONE, 1, 1);
        QuestWnd.Quest failed = quest(40, "Failed quest", QuestWnd.Quest.QST_FAIL, 2, 0);
        window.cqst.quests.add(current);
        window.cqst.quests.add(loading);
        window.dqst.quests.add(completed);
        window.dqst.quests.add(failed);
        QuestWnd.Quest.Box box = window.questbox.add(new QuestWnd.Quest.Box(10, current.res, current.title), Coord.z);
        box.cond = new QuestWnd.Quest.Condition[] {
            new QuestWnd.Quest.Condition("Meet the quest giver", 1, null),
            new QuestWnd.Quest.Condition("Collect branches", 0, "1/3")
        };
        window.quest = box;

        JSONObject snapshot = BridgeSnapshot.quests(window);
        check(snapshot.getString("scope").equals("loaded_quests"), "snapshot reports its scope");
        check(snapshot.getInt("selected_quest_id") == 10, "selected quest is identified");
        JSONArray quests = snapshot.getJSONArray("quests");
        check(quests.length() == 2, "all current quests survive a loading title");
        JSONObject active = quests.getJSONObject(0);
        check(active.getInt("id") == 10 && active.getString("title").equals("A new beginning"), "quest identity and title preserved");
        check(active.getInt("done") == 0 && active.getInt("ncond") == 2 && active.getInt("ndcond") == 1,
            "quest status and completion counts preserved");
        check(!active.getBoolean("title_loading") && active.getBoolean("objectives_loaded"), "loaded quest is marked ready");
        JSONArray objectives = active.getJSONArray("objectives");
        check(objectives.length() == 2 && objectives.getJSONObject(0).getString("desc").equals("Meet the quest giver"),
            "objective descriptions are returned in client order");
        check(objectives.getJSONObject(0).getInt("done") == 1 && objectives.getJSONObject(0).has("status")
            && objectives.getJSONObject(0).isNull("status"), "completed objective retains explicit null status");
        check(objectives.getJSONObject(1).getInt("done") == 0
            && objectives.getJSONObject(1).getString("status").equals("1/3"), "pending objective retains progress text");
        JSONObject unloaded = quests.getJSONObject(1);
        check(unloaded.isNull("title") && unloaded.getBoolean("title_loading"), "loading titles do not fail the request");
        check(unloaded.getInt("done") == 3 && !unloaded.getBoolean("objectives_loaded") && unloaded.isNull("objectives"),
            "another quest cannot inherit selected quest objectives");
        JSONArray history = snapshot.getJSONArray("completed_quests");
        check(history.length() == 2 && history.getJSONObject(0).getInt("done") == 1
            && history.getJSONObject(1).getInt("done") == 2, "completed and failed quests are distinct from current quests");
        check(window.quest == box && window.cqst.quests.get(0) == current && box.cond.length == 2,
            "reading does not select a quest or change client data");

        box.cond[1].status = "2/3";
        active = BridgeSnapshot.quests(window).getJSONArray("quests").getJSONObject(0);
        check(active.getJSONArray("objectives").getJSONObject(1).getString("status").equals("2/3"),
            "a later read observes updated objective progress");
        current.ncond = 3;
        active = BridgeSnapshot.quests(window).getJSONArray("quests").getJSONObject(0);
        check(!active.getBoolean("objectives_loaded") && active.getJSONArray("objectives").length() == 2,
            "partial objective data is marked incomplete");
        box.cond = new QuestWnd.Quest.Condition[0];
        active = BridgeSnapshot.quests(window).getJSONArray("quests").getJSONObject(0);
        check(!active.getBoolean("objectives_loaded"), "new detail boxes cannot pretend objectives are empty");
        current.ncond = 0;
        active = BridgeSnapshot.quests(window).getJSONArray("quests").getJSONObject(0);
        check(active.getBoolean("objectives_loaded") && active.getJSONArray("objectives").length() == 0,
            "a loaded quest with zero objectives is distinguishable from missing data");
        System.out.println("BridgeQuestSnapshotTest: " + checks + " checks passed");
    }

    private static QuestWnd.Quest quest(int id, String title, int done, int total, int finished) throws Exception {
        // The client normally constructs these private records from server messages.
        var constructor = QuestWnd.Quest.class.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        QuestWnd.Quest quest = constructor.newInstance(id);
        quest.title = title;
        quest.res = () -> { throw new Loading("Quest title is still loading"); };
        quest.done = done;
        quest.ncond = total;
        quest.ndcond = finished;
        return quest;
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
