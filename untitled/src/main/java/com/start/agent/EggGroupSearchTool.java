package com.start.agent;

import com.start.service.EggGroupDataCenter;

import java.util.*;

/**
 * 洛克王国宠物数据库查询工具，供 AI 调用。
 * 支持：查蛋（配对查询）、查蛋组、能否生蛋、查进化、预测蛋。
 */
public class EggGroupSearchTool implements Tool {

    private final EggGroupDataCenter dataCenter;

    public EggGroupSearchTool(EggGroupDataCenter dataCenter) {
        this.dataCenter = dataCenter;
    }

    @Override public String getName() { return "lokowang_pet_query"; }

    @Override public String getDescription() {
        return "查询洛克王国宠物信息。action取值：" +
               "查蛋(查询宠物蛋组及可配对宠物，需pet_name), " +
               "查蛋组(查询宠物所属蛋组，需pet_name；或查询蛋组包含的宠物，传入蛋组名到pet_name), " +
               "能否生蛋(判断两只宠物能否生蛋，需pet1和pet2), " +
               "查进化(查询宠物完整进化路径，需pet_name), " +
               "预测蛋(根据身高体重预测宠物种族，需size和weight), " +
               "help(查看可用命令帮助)。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string",
                                "description", "操作类型：查蛋/查蛋组/能否生蛋/查进化/预测蛋/help"),
                        "pet_name", Map.of("type", "string",
                                "description", "宠物名称（查蛋/查进化/查蛋组时使用）"),
                        "pet1", Map.of("type", "string",
                                "description", "第一只宠物名（能否生蛋时使用）"),
                        "pet2", Map.of("type", "string",
                                "description", "第二只宠物名（能否生蛋时使用）"),
                        "size", Map.of("type", "number",
                                "description", "身高（米），预测蛋时使用"),
                        "weight", Map.of("type", "number",
                                "description", "体重（千克），预测蛋时使用")
                ),
                "required", List.of("action"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String action = (String) args.get("action");
        if (action == null) return "缺少 action";

        return switch (action.trim()) {
            case "help" -> buildHelp();
            case "查蛋" -> queryMate(args);
            case "查蛋组" -> queryEggGroup(args);
            case "能否生蛋" -> checkCanBreed(args);
            case "查进化" -> queryEvolution(args);
            case "预测蛋" -> predictRace(args);
            default -> "未知 action: " + action + "，支持：查蛋/查蛋组/能否生蛋/查进化/预测蛋/help";
        };
    }

    private String buildHelp() {
        return """
               🎮 洛克王国宠物助手
               ━━━━━━━━━━━━━━━
               📌 可用查询：
               1️⃣ 查蛋 宠物名 — 查询宠物蛋组及可配对宠物
               2️⃣ 查蛋组 宠物名/蛋组名 — 查询蛋组信息
               3️⃣ 能否生蛋 宠物1 宠物2 — 判断两只宠物是否可以生蛋
               4️⃣ 查进化 宠物名 — 查询宠物的完整进化路径
               5️⃣ 预测蛋 身高 体重 — 根据身高体重预测宠物种族
               ━━━━━━━━━━━━━━━""";
    }

    private String queryMate(Map<String, Object> args) {
        String petName = (String) args.get("pet_name");
        if (petName == null || petName.isBlank()) return "❌ 请提供 pet_name";

        String groupName = dataCenter.getGroupName(petName.trim());
        if (groupName == null) return "❌ 找不到宠物 [" + petName.trim() + "] 的信息。";

        List<String> mates = dataCenter.getMates(petName.trim());
        StringBuilder sb = new StringBuilder();
        sb.append("✨ ").append(petName.trim()).append(" 属于 【").append(groupName).append("】\n");
        sb.append("🥚 可配对宠物（共").append(mates.size()).append("只）：\n");

        for (int i = 0; i < mates.size(); i++) {
            sb.append(mates.get(i));
            if (i < mates.size() - 1) sb.append("、");
            if ((i + 1) % 10 == 0 && i < mates.size() - 1) sb.append("\n");
        }
        return sb.toString();
    }

    private String queryEggGroup(Map<String, Object> args) {
        String input = (String) args.get("pet_name");
        if (input == null || input.isBlank()) return "❌ 请提供 pet_name（宠物名或蛋组名）";

        String trimmed = input.trim();
        if (isEggGroupName(trimmed)) {
            List<String> pets = dataCenter.getPetsInGroup(trimmed);
            if (pets == null || pets.isEmpty()) return "❌ 找不到蛋组 【" + trimmed + "】 的信息。";

            StringBuilder sb = new StringBuilder();
            sb.append("📋 【").append(trimmed).append("】共有 ").append(pets.size()).append(" 只宠物：\n\n");

            Map<String, List<String>> evolutionGroups = dataCenter.groupByEvolution(pets);
            int index = 0;
            for (Map.Entry<String, List<String>> entry : evolutionGroups.entrySet()) {
                String chain = entry.getKey();
                List<String> members = entry.getValue();
                index++;
                if (chain != null && !chain.equals("无进化")) {
                    sb.append(index).append(". ").append(chain).append("\n");
                } else {
                    for (String pet : members) {
                        sb.append(index).append(". ").append(pet).append("\n");
                        index++;
                    }
                }
            }
            return sb.toString();
        } else {
            String groups = dataCenter.getPetEggGroups(trimmed);
            if (groups == null) return "❌ 找不到宠物 [" + trimmed + "] 的信息。";

            String[] groupArray = groups.split(",");
            StringBuilder sb = new StringBuilder();
            sb.append("🏷️ ").append(trimmed).append(" 的蛋组：\n");
            for (int i = 0; i < groupArray.length; i++) {
                sb.append(i + 1).append(". ").append(groupArray[i].trim());
                if (i < groupArray.length - 1) sb.append("\n");
            }
            if (groupArray.length > 1) {
                sb.append("\n💡 提示：该宠物属于多个蛋组，可以与以上任一组中的宠物配对");
            }
            return sb.toString();
        }
    }

    private String checkCanBreed(Map<String, Object> args) {
        String pet1 = (String) args.get("pet1");
        String pet2 = (String) args.get("pet2");
        if (pet1 == null || pet1.isBlank() || pet2 == null || pet2.isBlank())
            return "❌ 请提供 pet1 和 pet2";

        pet1 = pet1.trim();
        pet2 = pet2.trim();
        boolean canBreed = dataCenter.canBreed(pet1, pet2);
        String groups1 = dataCenter.getPetEggGroups(pet1);
        String groups2 = dataCenter.getPetEggGroups(pet2);

        StringBuilder sb = new StringBuilder();
        if (canBreed) {
            sb.append("✅ ").append(pet1).append(" 和 ").append(pet2).append(" 可以生蛋！\n");
            if (groups1 != null && groups2 != null) {
                sb.append("🔗 共同蛋组：");
                for (String g1 : groups1.split(",")) {
                    for (String g2 : groups2.split(",")) {
                        if (g1.trim().equals(g2.trim())) {
                            sb.append("【").append(g1.trim()).append("】 ");
                        }
                    }
                }
            }
        } else {
            sb.append("❌ ").append(pet1).append(" 和 ").append(pet2).append(" 无法生蛋\n");
            if (groups1 != null && groups2 != null) {
                sb.append("📋 ").append(pet1).append(" 属于：").append(groups1).append("\n");
                sb.append("📋 ").append(pet2).append(" 属于：").append(groups2);
            }
        }
        return sb.toString();
    }

    private String queryEvolution(Map<String, Object> args) {
        String petName = (String) args.get("pet_name");
        if (petName == null || petName.isBlank()) return "❌ 请提供 pet_name";

        String chain = dataCenter.getEvolutionChain(petName.trim());
        if (chain == null || chain.isEmpty())
            return "❌ 找不到宠物 [" + petName.trim() + "] 的进化信息。";

        String[] forms = chain.split("\\s*→\\s*|\\s+");
        StringBuilder sb = new StringBuilder();
        sb.append("🧬 ").append(petName.trim()).append(" 的进化路径：\n");

        for (int i = 0; i < forms.length; i++) {
            if (i > 0) sb.append(" → ");
            if (forms[i].trim().equals(petName.trim())) {
                sb.append("【").append(forms[i].trim()).append("】");
            } else {
                sb.append(forms[i].trim());
            }
        }

        if (forms.length > 1) {
            if (forms[forms.length - 1].trim().equals(petName.trim()))
                sb.append("\n\n💡 这是最终形态，无法继续进化");
            else if (forms[0].trim().equals(petName.trim()))
                sb.append("\n\n💡 这是初始形态，还可以继续进化");
            else
                sb.append("\n\n💡 这是中间形态");
        }
        return sb.toString();
    }

    private String predictRace(Map<String, Object> args) {
        Object sizeObj = args.get("size");
        Object weightObj = args.get("weight");
        if (sizeObj == null || weightObj == null)
            return "❌ 请提供 size（身高，米）和 weight（体重，千克）";

        double size = Double.parseDouble(String.valueOf(sizeObj));
        double weight = Double.parseDouble(String.valueOf(weightObj));

        if (size <= 0 || size > 100 || weight <= 0 || weight > 10000)
            return "❌ 数值不合理，请输入有效的身高（米）和体重（千克）";

        List<EggGroupDataCenter.PredictResult> results = dataCenter.predictEggRace(size, weight);
        if (results.isEmpty())
            return "❌ 未找到匹配的宠物，请检查身高体重是否正确";

        StringBuilder sb = new StringBuilder();
        sb.append("🔮 预测结果（身高=").append(size).append("m, 体重=").append(weight).append("kg）：\n\n");
        sb.append("共找到 ").append(results.size()).append(" 个可能的种族：\n\n");

        for (int i = 0; i < results.size(); i++) {
            EggGroupDataCenter.PredictResult result = results.get(i);
            sb.append(i + 1).append(". ").append(result.name);
            sb.append("（").append(result.attributes).append("）");
            if (result.evolutionStage > 0)
                sb.append(" - 第").append(result.evolutionStage).append("阶段");
            sb.append("\n");
        }
        return sb.toString();
    }

    private boolean isEggGroupName(String text) {
        return text.contains("组") || text.contains("类") ||
               text.equals("机械") || text.equals("冰冰凉");
    }
}
