package dev.tanaka.wynnaibridge.translation;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;

import java.util.List;

/** Small deterministic regression suite for the Ability Tree API-input and component-style boundary. */
public final class AbilityTreeRegressionHarness {
    private static int assertions;

    private AbilityTreeRegressionHarness() {}

    public static void main(String[] args) {
        formattingGlyphAndFixedTermsStayOutOfProviderText();
        legacyFontGlyphsAndWynncraftLabelsStayProtected();
        protectedValuesAndNamesRestoreAroundTranslatedProse();
        requiredAbilityAndArchetypeNamesAreProtected();
        componentRebuildKeepsPreparedTextAndOriginalSiblings();
        abilityTreeMarkersDoNotClaimNormalItemLore();
        System.out.println("Ability Tree regression checks passed: " + assertions);
    }

    private static void legacyFontGlyphsAndWynncraftLabelsStayProtected() {
        String source = "✔À Combat Lv. Min: 15";
        AbilityTreeTextPreprocessor.PreparedText prepared = AbilityTreeTextPreprocessor.prepare(source, "");
        check(!prepared.requestText().contains("✔") && !prepared.requestText().contains("À"),
            "checkmark and Wynncraft's Latin-1 glyph must stay out of provider text");
        check(!prepared.requestText().contains("Combat") && !prepared.requestText().contains("15")
                && !prepared.requestText().contains(":") && !prepared.requestText().contains("."),
            "fixed labels, numeric values, and separators must stay out of provider text");
        check(prepared.restore(prepared.requestText()).equals("✔À 最低戦闘レベル: 15"),
            "protected icon, fixed label, separator, and value must restore in the original order");

        AbilityTreeTextPreprocessor.PreparedText classRequirement =
            AbilityTreeTextPreprocessor.prepare("Class Req: Archer/Hunter", "");
        check(!classRequirement.hasNaturalEnglish(), "class requirement labels and class names are local terms");
        check(classRequirement.restore(classRequirement.requestText()).equals("クラス要件: アーチャー/ハンター"),
            "class name glossary terms should be applied without sending them to DeepL");
        AbilityTreeTextPreprocessor.PreparedText classType = AbilityTreeTextPreprocessor.prepare("Class Type", "");
        check(classType.restore(classType.requestText()).equals("クラス要件"),
            "Wynncraft's Class Type label must not fall back to the misleading '授業の種類' translation");

        AbilityTreeTextPreprocessor.PreparedText woodcutting =
            AbilityTreeTextPreprocessor.prepare("Woodcutting Lv. Min: 15", "");
        check(woodcutting.restore(woodcutting.requestText()).equals("最低伐採レベル: 15"),
            "Woodcutting minimum level must use the profession glossary");

        AbilityTreeTextPreprocessor.PreparedText elemental =
            AbilityTreeTextPreprocessor.prepare("Thunder Main Attack Damage: +12", "");
        check(elemental.restore(elemental.requestText()).equals("雷属性メイン攻撃ダメージ: +12"),
            "Thunder Main Attack Damage must use the exact local glossary entry");

        for (String itemName : List.of("Flawless Oak Bow", "RARE BOW")) {
            AbilityTreeTextPreprocessor.PreparedText properName =
                AbilityTreeTextPreprocessor.prepare(itemName, "");
            check(!properName.hasNaturalEnglish() && properName.restore(properName.requestText()).equals(itemName),
                "standalone item names and rarity labels should remain unchanged");
        }
    }

    private static void formattingGlyphAndFixedTermsStayOutOfProviderText() {
        String glyph = "\uE05B";
        List<AbilityTreeTooltipPreview.StyledText> runs = AbilityTreeTooltipPreview.parseLegacyFormatting(
            "\u00A77\u00A7a✔ " + glyph + " \u00A77Total Damage: \u00A7f30% \u00A78(of your DPS, per hit)",
            Style.EMPTY
        );
        StringBuilder apiText = new StringBuilder();
        StringBuilder restored = new StringBuilder();
        for (AbilityTreeTooltipPreview.StyledText run : runs) {
            AbilityTreeTextPreprocessor.PreparedText prepared = AbilityTreeTextPreprocessor.prepare(run.text(), "Phantom Ray");
            apiText.append(prepared.requestText());
            restored.append(prepared.restore(prepared.requestText()));
        }

        check(!apiText.toString().contains("\u00A7"), "legacy formatting codes must be removed before translation");
        check(!apiText.toString().contains(glyph), "Wynncraft private-use glyph must not reach translation");
        check(!apiText.toString().contains("✔"), "UI icon glyph must not reach translation");
        check(!apiText.toString().contains("Total Damage"), "fixed Ability Tree labels must use the local glossary");
        check(!apiText.toString().contains("30%"), "percentage values must be protected");
        check(!apiText.toString().contains("DPS"), "DPS abbreviation must be protected");
        check(apiText.toString().contains("of your") && apiText.toString().contains("per hit"),
            "ordinary explanation must remain available to the translator");
        check(restored.toString().contains("✔ " + glyph + " 総ダメージ: 30%"),
            "fixed label, original glyph, and numeric value must restore in order");
    }

    private static void protectedValuesAndNamesRestoreAroundTranslatedProse() {
        String source = "Condense Arrow Storm into a single ray that damages enemies 10 times per second.";
        AbilityTreeTextPreprocessor.PreparedText prepared = AbilityTreeTextPreprocessor.prepare(source, "Phantom Ray");
        check(!prepared.requestText().contains("Arrow Storm"), "ability name must not be sent to the provider");
        check(!prepared.requestText().contains("10"), "numeric value must not be sent to the provider");
        check(prepared.requestText().contains("Condense") && prepared.requestText().contains("single ray"),
            "English explanatory prose must be sent to the provider");

        String simulatedJapanese = prepared.requestText()
            .replace("Condense", "集約")
            .replace("into a single ray that damages enemies", "して、単一の光線で敵にダメージを与え")
            .replace("times per second", "回/秒");
        String restored = prepared.restore(simulatedJapanese);
        check(restored.contains("Arrow Storm"), "ability name must return unchanged after translation");
        check(restored.contains("10") && restored.contains("回/秒"), "numeric value must survive translated prose");
    }

    private static void requiredAbilityAndArchetypeNamesAreProtected() {
        String raw = "\u00A77\u00A7nRequired Ability: \u00A7fArrow Storm";
        List<AbilityTreeTooltipPreview.StyledText> runs = AbilityTreeTooltipPreview.parseLegacyFormatting(raw, Style.EMPTY);
        StringBuilder apiText = new StringBuilder();
        StringBuilder sourceDisplay = new StringBuilder();
        int removedCodes = 0;
        for (AbilityTreeTooltipPreview.StyledText run : runs) {
            removedCodes += run.removedFormattingCodes();
            AbilityTreeTextPreprocessor.PreparedText prepared = AbilityTreeTextPreprocessor.prepare(run.text(), "Phantom Ray");
            apiText.append(prepared.requestText());
            sourceDisplay.append(prepared.restore(prepared.requestText()));
        }

        check(removedCodes == 3, "all legacy formatting codes must be consumed");
        check(!apiText.toString().contains("Required Ability") && !apiText.toString().contains("Arrow Storm"),
            "fixed label and prerequisite ability name must stay out of provider text");
        check(sourceDisplay.toString().contains("必要アビリティ: Arrow Storm"),
            "required-ability label translates locally while its ability name stays unchanged");
        List<AbilityTreeTooltipPreview.StyledText> visibleRuns = runs.stream().filter(run -> !run.text().isEmpty()).toList();
        check(visibleRuns.size() == 2 && visibleRuns.getFirst().style().isUnderlined(),
            "underline style must survive when text moves into real Component runs");
        check(visibleRuns.getFirst().style().getColor().getValue() == 0xAAAAAA,
            "legacy gray color must become a Style color");
        check(visibleRuns.getLast().style().getColor().getValue() == 0xFFFFFF,
            "legacy white color must become a Style color");

        for (String name : List.of("Phantom Ray", "Bryophyte Roots", "Nimble String")) {
            AbilityTreeTextPreprocessor.PreparedText named = AbilityTreeTextPreprocessor.prepare(
                "Unlocking will block: " + name, "Test Ability"
            );
            check(!named.requestText().contains(name), name + " must not be sent to the provider");
            check(named.restore(named.requestText()).endsWith(name), name + " must be restored unchanged");
        }

        AbilityTreeTextPreprocessor.PreparedText archetype = AbilityTreeTextPreprocessor.prepare(
            "Min Trapper Archetype: 2", "Test Ability"
        );
        check(!archetype.requestText().contains("Trapper"), "archetype name must be protected");
        check(!archetype.requestText().contains("2"), "archetype requirement value must be protected");
        check(archetype.restore(archetype.requestText()).contains("必要なTrapperアーキタイプ数: 2"),
            "minimum archetype label should be localized without changing the archetype name or value");

        AbilityTreeTextPreprocessor.PreparedText range = AbilityTreeTextPreprocessor.prepare(
            "Range: ±26 Blocks", "Test Ability"
        );
        check(!range.requestText().contains("Range") && !range.requestText().contains("26")
                && !range.requestText().contains("Blocks"),
            "fixed range label, block value, and unit must stay out of provider text");
        check(range.restore(range.requestText()).contains("射程: ±26 ブロック"),
            "range and block units should be localized while the value and sign remain unchanged");
    }

    private static void abilityTreeMarkersDoNotClaimNormalItemLore() {
        check(AbilityTreeTooltipPreview.isAbilityTreeTooltip(List.of(
            Component.literal("Phantom Ray"), Component.literal("\u00A77Total Damage: 30%")
        )), "Ability Tree signature should select the feature-specific pipeline");
        check(!AbilityTreeTooltipPreview.isAbilityTreeTooltip(List.of(
            Component.literal("Powder"), Component.literal("+4-5 Earth Damage")
        )), "ordinary item lore must remain on the existing Tooltip pipeline");
    }

    private static void componentRebuildKeepsPreparedTextAndOriginalSiblings() {
        String glyph = "\uE05B";
        Component source = Component.literal("\u00A77Fire Damage:")
            .append(Component.literal(glyph).setStyle(Style.EMPTY.withColor(0x55FFFF)))
            .append(Component.literal(" +25"));

        AbilityTreeTooltipPreview.ComponentTransform rebuilt =
            AbilityTreeTooltipPreview.translateComponent(source, "");
        String result = rebuilt.component().getString();
        check(result.startsWith("火属性ダメージ:"), "rebuilt Japanese label must remain in the Component");
        check(result.contains(glyph) && result.endsWith(" +25"),
            "glyph and numeric sibling Components must survive rebuilding");
        check(rebuilt.component().getSiblings().size() >= 3,
            "translation runs and original glyph/value siblings must coexist after rebuilding");
        Component japaneseRun = rebuilt.component().getSiblings().stream()
            .filter(child -> child.getString().equals("火属性ダメージ"))
            .findFirst().orElseThrow();
        check(FontDescription.DEFAULT.equals(japaneseRun.getStyle().getFont()),
            "fixed Japanese text must use the Minecraft default font");
        Component glyphRun = rebuilt.component().getSiblings().stream()
            .filter(child -> child.getString().equals(glyph))
            .findFirst().orElseThrow();
        check(glyphRun.getStyle().getColor().getValue() == 0x55FFFF,
            "the original glyph color must survive component reconstruction");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
