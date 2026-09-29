package haven.bridge;

import haven.*;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Skill icons are GridList model entries, not child widgets. */
final class BridgeSkills {
    static JSONObject snapshot(CharWnd character, BridgeWidgets widgets) {
        if (character == null || character.skill == null)
            return new JSONObject().put("available", false);
        SkillWnd window = character.skill;
        return grid(window.skg).put("available", true).put("learning_points", character.exp)
            .put("widget_id", widgets.id(window.skg)).put("scope", "loaded_skills");
    }

    static JSONObject grid(SkillWnd.SkillGrid grid) {
        CharWnd character = grid.getparent(CharWnd.class);
        Integer lp = character == null ? null : character.exp;
        return new JSONObject().put("available_skills", rows(grid.nsk.items, lp))
            .put("known_skills", rows(grid.csk.items, lp))
            .put("selected_skill_id", grid.sel == null ? JSONObject.NULL : grid.sel.nm);
    }

    private static JSONArray rows(List<SkillWnd.Skill> skills, Integer lp) {
        JSONArray rows = new JSONArray();
        for (SkillWnd.Skill skill : skills) {
            JSONObject row = new JSONObject().put("id", skill.nm).put("cost", skill.cost).put("known", skill.has)
                .put("affordable", lp == null ? JSONObject.NULL : !skill.has && skill.cost <= lp)
                .put("name", JSONObject.NULL).put("resource", JSONObject.NULL);
            try {
                Resource res = skill.res.get();
                row.put("resource", res.name).put("loading", false);
                Resource.Tooltip tip = res.layer(Resource.tooltip);
                if (tip != null) BridgeWidgets.putText(row, "name", tip.t);
            } catch (Loading e) { row.put("loading", true); }
            rows.put(row);
        }
        return rows;
    }

    static JSONObject action(CharWnd character, BridgeWidgets widgets, JSONObject args) {
        if (character == null || character.skill == null)
            return error("skills_unavailable", "Character skills have not loaded yet");
        SkillWnd window = character.skill;
        String id = args.getString("skill_id");
        SkillWnd.Skill skill = window.skg.nsk.items.stream().filter(s -> s.nm.equals(id)).findFirst().orElse(null);
        boolean buy = args.getString("action").equals("buy");
        if (skill == null) {
            skill = window.skg.csk.items.stream().filter(s -> s.nm.equals(id)).findFirst().orElse(null);
            if (skill != null && buy) return error("skill_known", "This skill is already known");
        }
        if (skill == null) return error("skill_missing", "Read get_skills and use a currently loaded skill id");
        if (buy) {
            if (character.exp < skill.cost) return error("insufficient_lp", "Not enough learning points for this skill");
            for (Widget child : window.children()) {
                if (child instanceof CharWnd.TabProxy proxy && proxy.id.equals("skill") && widgets.server(proxy.wdgid()) == proxy) {
                    proxy.wdgmsg("buy", skill.nm);
                    return new JSONObject().put("status", "purchase_sent").put("skill_id", skill.nm)
                        .put("next", "Read get_skills and get_state.messages for the server result");
                }
            }
            return error("skills_unavailable", "The server skill widget has not loaded yet");
        }
        BridgeWindows.show(character, character.skilltab);
        if (window.skg.parent instanceof Tabs.Tab tab) tab.showtab();
        window.skg.change(skill);
        return new JSONObject().put("status", "selected").put("skill_id", skill.nm).put("cost", skill.cost);
    }

    private static JSONObject error(String code, String message) { return BridgeServer.error(code, message); }
}
