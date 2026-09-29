package haven.bridge;

import haven.CharWnd;
import haven.CheckBox;
import haven.Coord;
import haven.OptWnd;
import haven.QuestWnd;
import haven.Widget;
import haven.Window;

/** Exercises real window/tab visibility without a game connection. */
public final class BridgeWindowsTest {
    private static int checks;

    public static void main(String[] args) {
        System.setProperty("haven.uiscale", "1");
        System.setProperty("java.awt.headless", "true");
        // Normally initialized by GameUI's Options window before any other window is added.
        OptWnd.stackWindowsWhenOpenedCheckBox = new CheckBox("");
        check(!BridgeWindows.show(null, null), "missing windows are not opened");
        check(!BridgeWindows.state(null, null).getBoolean("available"), "missing windows are unavailable");

        Widget desktop = new Widget(new Coord(1200, 900));
        desktop.setfocusctl(true);
        CharWnd character = new CharWnd(null);
        character.hide();
        check(!BridgeWindows.show(character, character.questtab), "unattached windows are not opened");
        desktop.add(character, Coord.z);
        character.addchild(new QuestWnd(), "tab");
        check(!BridgeWindows.state(character, null).getBoolean("visible"), "hidden character sheet is reported hidden");
        check(BridgeWindows.show(character, null), "character sheet can be opened");
        check(BridgeWindows.state(character, null).getBoolean("visible"), "opened character sheet is visible");
        check(!BridgeWindows.state(character, character.questtab).getBoolean("visible"),
            "showing the character sheet does not imply the quest tab is open");
        check(BridgeWindows.show(character, character.questtab), "quest log can be opened");
        check(BridgeWindows.state(character, character.questtab).getBoolean("visible") && !character.battrtab.visible(),
            "opening quests selects the quest tab rather than leaving the attributes tab visible");
        check(desktop.focused == character && desktop.lchild == character, "opened window is focused and raised");
        check(BridgeWindows.show(character, character.questtab)
            && BridgeWindows.state(character, character.questtab).getBoolean("visible"),
            "repeated quest opens never toggle it closed");
        check(character.quest.quest == null, "opening the quest tab does not select a quest");
        character.hide();
        check(!BridgeWindows.state(character, character.questtab).getBoolean("visible"),
            "a closing animation is already reported hidden");
        check(BridgeWindows.show(character, character.questtab)
            && BridgeWindows.state(character, character.questtab).getBoolean("visible"),
            "opening interrupts a closing animation");
        desktop.hide();
        check(!BridgeWindows.state(character, character.questtab).getBoolean("visible"),
            "hidden ancestors are not reported as visible windows");
        desktop.show();

        Window inventory = new Window(new Coord(100, 100), "Inventory");
        inventory.hide();
        desktop.add(inventory, Coord.z);
        check(BridgeWindows.show(inventory, null) && BridgeWindows.state(inventory, null).getBoolean("visible"),
            "ordinary windows open without a tab");
        check(desktop.focused == inventory && desktop.lchild == inventory, "ordinary windows are focused and raised");
        System.out.println("BridgeWindowsTest: " + checks + " checks passed");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
