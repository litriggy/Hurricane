package haven.bridge;

import haven.*;
import haven.iosys.audio.DummyAudio;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises real UI dispatch, handle lifetime and outgoing game protocol without an account. */
public final class BridgeAccessTest {
    private static int checks;
    private static final class Sent {
        final int id;
        final String message;
        final Object[] args;
        Sent(int id, String message, Object[] args) { this.id = id; this.message = message; this.args = args; }
    }
    private static final List<Sent> sent = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        System.setProperty("haven.uiscale", "1");
        System.setProperty("java.awt.headless", "true");
        OptWnd.stackWindowsWhenOpenedCheckBox = new CheckBox("");
        UI ui = new UI(null, new Audio.Root(DummyAudio.DummySink.instance), new Coord(1000, 800), null);
        try {
            ui.setreceiver((id, message, values) -> sent.add(new Sent(id, message, values)));
            Widget game = ui.root.add(new Widget(new Coord(900, 700)), Coord.z);
            game.setfocusctl(true);
            ui.bind(game, 1000);
            BridgeWidgets access = new BridgeWidgets(game);
            Widget unrelated = ui.root.add(new Widget(new Coord(10, 10)), Coord.z);
            ui.bind(unrelated, 900);
            check(access.id(unrelated) == null && access.server(900) == null, "handles cannot reach outside the game tree");
            Window window = game.add(new Window(new Coord(300, 200), "Fixture"), Coord.z);
            Button button = window.add(new Button(90, "Do action") {
                protected void depress() {}
                protected void unpress() {}
            }, new Coord(10, 10));
            ui.bind(button, 1001);
            TextEntry input = window.add(new TextEntry(100, "initial"), new Coord(10, 50));
            input.canactivate = true;
            ui.bind(input, 1002);
            TextEntry password = window.add(new TextEntry(100, "do-not-expose"), new Coord(10, 80));
            password.pw = true;
            CheckBox checkbox = window.add(new CheckBox("Enabled"), new Coord(10, 110));
            HSlider slider = window.add(new HSlider(100, 0, 10, 2), new Coord(10, 140));
            String id = access.id(button);
            check(access.id(button).equals(id), "handles stay stable");
            JSONObject tree = access.snapshot(new JSONObject().put("include_hidden", true));
            check(!tree.toString().contains("do-not-expose"), "password text is redacted");
            check(tree.getJSONArray("widgets").length() > 5, "real window children are discoverable");
            check(access.snapshot(new JSONObject().put("limit", 1)).getInt("next_offset") == 1, "UI results paginate");

            int before = sent.size();
            JSONObject click = access.action(action(id, "click"));
            check(click.getBoolean("handled") && sent.size() == before + 1 && last().message.equals("activate"), "button click executes the normal handler exactly once");
            button.disable(true);
            before = sent.size();
            check(code(access.action(action(id, "click"))).equals("widget_disabled") && sent.size() == before, "disabled controls do not send commands");
            button.disable(false);

            access.action(action(access.id(input), "set_text").put("text", "updated"));
            check(input.text().equals("updated"), "input edits reach the real text buffer");
            access.action(action(access.id(input), "activate"));
            check(last().message.equals("activate") && last().args[0].equals("updated"), "input submit uses the current buffer");
            check(code(access.action(action(access.id(password), "set_text").put("text", "x"))).equals("protected_widget"), "password edits are excluded");
            access.action(action(access.id(checkbox), "set_checked").put("checked", true));
            check(checkbox.state(), "checkbox state changes");
            check(code(access.action(action(access.id(slider), "set_value").put("value", 11))).equals("out_of_range") && slider.val == 2, "invalid slider input leaves its state unchanged");
            access.action(action(access.id(slider), "set_value").put("value", 7));
            check(slider.val == 7, "valid slider input applies");

            window.hide();
            check(code(access.action(action(id, "click"))).equals("widget_hidden"), "hidden controls do not click");
            access.action(action(access.id(window), "show"));
            check(window.visible() && window.tvisible(), "show opens the enclosing window");
            ui.modshift = true;
            try {
                BridgeWidgets.modifiers(ui, UI.MOD_CTRL, () -> {
                    check(ui.modctrl && !ui.modshift, "requested modifiers apply within dispatch");
                    throw new IllegalStateException("fixture");
                });
            } catch (IllegalStateException expected) {}
            check(ui.modshift && !ui.modctrl, "modifiers restore even if a handler fails");

            access.message(new JSONObject().put("widget_id", id).put("message", "custom")
                .put("arguments", new JSONArray().put(new JSONObject().put("type", "coord").put("x", 3).put("y", -4)).put("value")));
            check(last().id == 1001 && last().message.equals("custom") && last().args[0].equals(new Coord(3, -4)), "typed message targets the discovered server widget");
            check(code(access.message(new JSONObject().put("widget_id", access.id(checkbox)).put("message", "ch").put("arguments", new JSONArray()))).equals("client_only_widget"), "unregistered controls cannot emit raw server messages");
            button.destroy();
            check(access.find(id) == null && code(access.action(action(id, "click"))).equals("widget_missing"), "destroyed handles cannot target replacement widgets");
            Button replacement = window.add(new Button(90, "Replacement"), Coord.z);
            check(!access.id(replacement).equals(id), "handles are never reused in a session");
            check(code(access.action(action(access.id(input), "click").put("x", 999).put("y", 0))).equals("out_of_range"), "off-control coordinates rejected");

            progressChecks(game, access);
            protocolChecks(game);
            requestChecks();
            System.out.println("BridgeAccessTest: " + checks + " checks passed");
        } finally { ui.destroy(); }
    }

    private static void progressChecks(Widget game, BridgeWidgets access) throws Exception {
        CharWnd character = game.add(new CharWnd(null), Coord.z);
        character.exp = 2075;
        character.addchild(new SkillWnd(), "tab");
        SkillWnd skills = character.skill;
        var constructor = SkillWnd.Skill.class.getDeclaredConstructor(SkillWnd.class, String.class, Indir.class, int.class, boolean.class);
        constructor.setAccessible(true);
        Indir<Resource> loading = () -> { throw new Loading("Fixture icon still loading"); };
        SkillWnd.Skill farming = constructor.newInstance(skills, "farming", loading, 400, false);
        skills.skg.nsk.update(new ArrayList<>(List.of(farming)));
        character.hide();
        JSONObject grid = access.describe(skills.skg);
        check(grid.has("available_skills") && grid.getJSONArray("available_skills").length() == 1,
            "hidden icon grids must expose skills even though icons are not child widgets");
        JSONObject row = grid.getJSONArray("available_skills").getJSONObject(0);
        check(row.getString("id").equals("farming") && row.getInt("cost") == 400 && row.getBoolean("loading"),
            "loading skill artwork must not hide the server identity or LP cost");
        check(skills.skg.sel == null, "reading a skill grid must not select or buy a skill");

        SkillWnd.Skill known = constructor.newInstance(skills, "foraging", loading, 0, true);
        skills.skg.csk.update(new ArrayList<>(List.of(known)));
        CharWnd.TabProxy proxy = new CharWnd.TabProxy(SkillWnd.class, "skill");
        character.addchild(proxy, "tab");
        game.ui.bind(proxy, 2201);
        JSONObject state = BridgeSkills.snapshot(character, access);
        check(state.getInt("learning_points") == 2075 && state.getJSONArray("known_skills").length() == 1
            && state.getJSONArray("available_skills").getJSONObject(0).getBoolean("affordable"),
            "skill snapshots distinguish known skills and current affordability without opening tabs");
        int before = sent.size();
        BridgeSkills.action(character, access, skillAction("select", "farming"));
        check(skills.skg.sel == farming && BridgeWidgets.visible(skills.skg) && sent.size() == before,
            "selecting a skill opens both tabs without sending a purchase");
        BridgeSkills.action(character, access, skillAction("buy", "farming"));
        check(sent.size() == before + 1 && last().id == 2201 && last().message.equals("buy")
            && last().args.length == 1 && last().args[0].equals("farming") && character.exp == 2075,
            "purchases target the server skill proxy exactly once and leave LP confirmation to the server");
        before = sent.size();
        character.exp = 399;
        check(code(BridgeSkills.action(character, access, skillAction("buy", "farming"))).equals("insufficient_lp"),
            "insufficient LP must reject a purchase before dispatch");
        check(code(BridgeSkills.action(character, access, skillAction("buy", "foraging"))).equals("skill_known"),
            "already known skills cannot be bought twice");
        check(code(BridgeSkills.action(character, access, skillAction("buy", "invented"))).equals("skill_missing") && sent.size() == before,
            "unknown and invalid purchases never reach the game server");

        ISBox materials = game.add(new ISBox(loading, 0, 5, 0), Coord.z);
        materials.uimsg("chnum", 2, 5, 1);
        JSONObject material = access.describe(materials).getJSONObject("material");
        check(material.getJSONArray("counts").toString().equals("[2,5,1]") && material.getString("label").equals("2/5/1"),
            "construction material snapshots must follow server updates instead of constructor counts");
        check(material.getBoolean("loading"), "loading material icons must not hide construction counts");

        ChatUI.Log log = new ChatUI.Log("System");
        for (int i = 0; i < 25; i++)
            log.rmsgs.add(log.new RenderedMessage(new ChatUI.Channel.SimpleMessage("Message " + i, null), i, 300));
        JSONArray messages = BridgeSnapshot.messages(log);
        check(messages.length() == 20 && messages.getJSONObject(0).getInt("index") == 5
            && messages.getJSONObject(19).getString("text").endsWith("Message 24") && BridgeSnapshot.messageCount(log) == 25,
            "bounded system messages keep their cursor so old failures cannot be confused with new commands");
        check(BridgeSnapshot.messages(null).length() == 0 && BridgeSnapshot.messageCount(null) == 0,
            "a missing system log must not break state reads");
    }

    private static JSONObject skillAction(String action, String id) {
        return new JSONObject().put("action", action).put("skill_id", id);
    }

    private static void protocolChecks(Widget map) {
        Coord2d at = new Coord2d(-11.5, 22.5);
        BridgeProtocol.objectClick(map, 4_000_000_001L, at, 3, 2, false);
        check(last().message.equals("click") && last().args.length == 9 && last().args[2].equals(3) &&
            last().args[5].equals((int)4_000_000_001L) && last().args[1].equals(at.floor(OCache.posres)), "object clicks preserve unsigned gob ID bits, modifiers and coordinate resolution");
        BridgeProtocol.objectClick(map, 4_000_000_001L, at, 3, 4, true);
        check(last().message.equals("itemact") && last().args.length == 8 && last().args[2].equals(4), "held-item use has the correct object-click argument layout");
        BridgeProtocol.ground(map, new JSONObject().put("action", "select_area").put("x", -0.5).put("y", 0).put("x2", 22).put("y2", 33));
        check(last().message.equals("sel") && last().args[0].equals(new Coord(-1, 0)) && last().args[1].equals(new Coord(2, 3)), "area selection floors negative world coordinates into tiles");
        BridgeProtocol.ground(map, new JSONObject().put("action", "place").put("x", 0).put("y", 0).put("angle", 90));
        check(last().args[1].equals(16384), "placement converts degrees to protocol angle");
        Object[] decoded = BridgeProtocol.values(new JSONArray("[null,1,1.5,{\"type\":\"long\",\"value\":\"9007199254740993\"},{\"type\":\"bytes\",\"value\":[0,255]},[{\"type\":\"coord2d\",\"x\":0.5,\"y\":-1}]]"));
        check(decoded[0] == null && decoded[1] instanceof Integer && decoded[2] instanceof Double && decoded[3].equals(9007199254740993L) &&
            ((byte[])decoded[4])[1] == (byte)255 && ((Object[])decoded[5])[0] instanceof Coord2d, "typed decoding retains integers, precision, bytes, nesting and coordinate types");
    }

    private static void requestChecks() {
        for (String json : new String[] {
            "{\"method\":\"get_skills\",\"arguments\":{}}",
            "{\"method\":\"skill_action\",\"arguments\":{\"session_id\":\"s\",\"action\":\"select\",\"skill_id\":\"farming\"}}",
            "{\"method\":\"map_action\",\"arguments\":{\"session_id\":\"s\",\"action\":\"preview\",\"x\":0,\"y\":0,\"angle\":90}}",
            "{\"method\":\"interact\",\"arguments\":{\"session_id\":\"s\",\"target_id\":\"12\"}}",
            "{\"method\":\"interact\",\"arguments\":{\"session_id\":\"s\",\"target_id\":\"12\",\"action\":\"item_use\",\"button\":1,\"modifiers\":3}}",
            "{\"method\":\"map_action\",\"arguments\":{\"session_id\":\"s\",\"action\":\"select_area\",\"x\":0,\"y\":0,\"x2\":11,\"y2\":22}}",
            "{\"method\":\"craft\",\"arguments\":{\"session_id\":\"s\",\"crafting_id\":12,\"all\":false}}",
            "{\"method\":\"combat_action\",\"arguments\":{\"session_id\":\"s\",\"action\":\"release\",\"slot\":0}}",
            "{\"method\":\"ui_action\",\"arguments\":{\"session_id\":\"s\",\"widget_id\":\"u1\",\"action\":\"set_text\",\"text\":\"\"}}"
        }) check(BridgeRequest.parse(new JSONObject(json)) != null, "valid gameplay request");
        for (String json : new String[] {
            "{\"method\":\"skill_action\",\"arguments\":{\"action\":\"buy\",\"skill_id\":\"farming\"}}",
            "{\"method\":\"skill_action\",\"arguments\":{\"session_id\":\"s\",\"action\":\"buy\",\"skill_id\":\"\"}}",
            "{\"method\":\"map_action\",\"arguments\":{\"session_id\":\"s\",\"action\":\"preview\",\"x\":0,\"y\":0}}",
            "{\"method\":\"craft\",\"arguments\":{\"crafting_id\":12,\"all\":true}}",
            "{\"method\":\"craft\",\"arguments\":{\"session_id\":\"s\",\"crafting_id\":12,\"all\":1}}",
            "{\"method\":\"item_action\",\"arguments\":{\"session_id\":\"s\",\"item_id\":-1,\"action\":\"take\"}}",
            "{\"method\":\"inventory_drop\",\"arguments\":{\"session_id\":\"s\",\"inventory_id\":12,\"x\":0.5,\"y\":0}}",
            "{\"method\":\"map_action\",\"arguments\":{\"session_id\":\"s\",\"action\":\"place\",\"x\":0,\"y\":0}}",
            "{\"method\":\"map_action\",\"arguments\":{\"session_id\":\"s\",\"action\":\"click\",\"x\":0,\"y\":0,\"angle\":1}}",
            "{\"method\":\"combat_action\",\"arguments\":{\"session_id\":\"s\",\"action\":\"use\",\"slot\":0,\"x\":0}}",
            "{\"method\":\"combat_action\",\"arguments\":{\"session_id\":\"s\",\"action\":\"peace\",\"target_id\":\"9223372036854775808\"}}",
            "{\"method\":\"ui_action\",\"arguments\":{\"session_id\":\"s\",\"widget_id\":\"u1\",\"action\":\"click\",\"text\":\"x\"}}",
            "{\"method\":\"widget_message\",\"arguments\":{\"session_id\":\"s\",\"widget_id\":\"u1\",\"message\":\"act\",\"arguments\":[true]}}",
            "{\"method\":\"widget_message\",\"arguments\":{\"session_id\":\"s\",\"widget_id\":\"u1\",\"message\":\"act\",\"arguments\":[{\"type\":\"coord\",\"x\":1.5,\"y\":0}]}}",
            "{\"method\":\"widget_message\",\"arguments\":{\"session_id\":\"s\",\"widget_id\":\"u1\",\"message\":\"act\",\"arguments\":[{\"type\":\"bytes\",\"value\":[256]}]}}"
        }) {
            boolean rejected = false;
            try { BridgeRequest.parse(new JSONObject(json)); }
            catch (IllegalArgumentException | org.json.JSONException expected) { rejected = true; }
            check(rejected, "malformed gameplay request rejected before dispatch");
        }
        boolean deepRejected = false;
        try { BridgeProtocol.values(new JSONArray("[[[[[[0]]]]]]")); }
        catch (IllegalArgumentException expected) { deepRejected = true; }
        check(deepRejected, "excessive message nesting rejected");
    }

    private static JSONObject action(String id, String action) { return new JSONObject().put("widget_id", id).put("action", action); }
    private static Sent last() { return sent.getLast(); }
    private static String code(JSONObject result) { return result.getJSONObject("error").getString("code"); }
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
    }
}
