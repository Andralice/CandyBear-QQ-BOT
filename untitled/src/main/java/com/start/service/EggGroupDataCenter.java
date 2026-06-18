package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 蛋组数据中心
 * 职责：1. 每天凌晨自动更新数据
 *      2. 提供线程安全的查询接口
 *      3. 断网时自动降级读取本地缓存
 */
public class EggGroupDataCenter {

    private static final Logger logger = LoggerFactory.getLogger(EggGroupDataCenter.class);
    private static final String DATA_FILE_PATH = "egg_group_cache.json";
    private static final String ALL_PETS_RESOURCE_PATH = "/pets/all_pets.json";
    private static final String EGG_GROUP_API_URL = "https://roco.gptvip.chat/api/egg-group-members?group_id=%d&page=1&page_size=100";
    private static final String EGG_PREDICT_API_URL = "https://wiki.lcx.cab/lk/egg_group_query.php?action=predict&size=%.3f&weight=%.3f";

    // 使用 AtomicReference 保证数据更新时的线程安全（读写分离）
    private final AtomicReference<Map<String, String>> petToGroupMap = new AtomicReference<>(new HashMap<>());
    private final AtomicReference<Map<String, List<String>>> groupToPetsMap = new AtomicReference<>(new HashMap<>());
    private final AtomicReference<Map<String, String>> evolutionChainsMap = new AtomicReference<>(new HashMap<>()); // 宠物 -> 完整进化链

    // 定时任务调度器
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public EggGroupDataCenter() {
        // 1. 启动时先加载本地缓存（防止刚启动没数据）
        boolean loaded = loadLocalData();
        
        if (!loaded) {
            logger.info("📝 本地缓存不存在，将使用演示数据初始化...");
            initializeDemoData();
        }

        // 2. 启动时立即尝试从远程 API 批量更新一次（确保使用最新数据）
        logger.info("🔄 正在从远程 API 批量更新数据...");
        batchUpdateFromRemote();

        // 3. 设置定时任务：每周日凌晨 03:00 更新（降低频率避免API压力）
        long initialDelay = getDelayUntilNextRun(3, 0);

        scheduler.scheduleAtFixedRate(() -> {
            logger.info("⏰ 触发定时任务：开始批量更新蛋组数据...");
            batchUpdateFromRemote();
        }, initialDelay, 7, TimeUnit.DAYS); // 改为每周更新

        logger.info("✅ 蛋组数据中心已启动，下次更新将在 " + initialDelay/3600000 + " 小时后");
    }

    /**
     * 批量从远程 API 更新所有宠物数据（优化版：直接获取蛋组）
     */
    private void batchUpdateFromRemote() {
        logger.info("📋 开始从新 API 获取蛋组数据...");
        
        Map<String, String> newPetToGroup = new HashMap<>();
        Map<String, List<String>> newGroupToPets = new HashMap<>();
        Map<String, String> newEvolutionChains = new HashMap<>(); // 存储进化链
        
        int successGroups = 0;
        int failedGroups = 0;
        int totalPets = 0;
        
        // 遍历所有蛋组（2-15，跳过 group_id=1）
        for (int groupId = 2; groupId <= 15; groupId++) {
            try {
                Thread.sleep(300);
                
                logger.debug("🔍 查询蛋组 [" + groupId + "/14]");
                
                String url = String.format(EGG_GROUP_API_URL, groupId);
                String json = fetchDataFromUrl(url);
                
                if (json != null && !json.isEmpty()) {
                    // 提取蛋组名称和宠物列表
                    EggGroupInfo groupInfo = parseEggGroupWithId(json, groupId);
                    
                    if (groupInfo != null && !groupInfo.pets.isEmpty()) {
                        String groupName = groupInfo.groupName;
                        List<String> petsInGroup = groupInfo.pets;
                        
                        // 添加到总数据中
                        for (String pet : petsInGroup) {
                            String existingGroup = newPetToGroup.get(pet);
                            if (existingGroup == null) {
                                newPetToGroup.put(pet, groupName);
                            } else if (!existingGroup.contains(groupName)) {
                                newPetToGroup.put(pet, existingGroup + "," + groupName);
                            }
                        }
                        
                        newGroupToPets.put(groupName, petsInGroup);
                        
                        // 保存进化链信息
                        for (Map.Entry<String, String> entry : groupInfo.evolutionChains.entrySet()) {
                            newEvolutionChains.put(entry.getKey(), entry.getValue());
                        }
                        
                        totalPets += petsInGroup.size();
                        successGroups++;
                        logger.debug("✅ " + groupName + " 解析成功: " + petsInGroup.size() + " 只宠物");
                    } else {
                        failedGroups++;
                    }
                } else {
                    failedGroups++;
                    logger.warn("❌ 蛋组 " + groupId + " 查询失败");
                }
            } catch (Exception e) {
                failedGroups++;
                logger.warn("❌ 蛋组 " + groupId + " 查询异常: " + e.getMessage());
            }
        }
        
        // 原子替换内存中的数据
        petToGroupMap.set(newPetToGroup);
        groupToPetsMap.set(newGroupToPets);
        evolutionChainsMap.set(newEvolutionChains);
        
        // 保存到本地缓存
        try {
            String cacheJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(convertToCacheFormat(newPetToGroup, newGroupToPets));
            saveLocalData(cacheJson);
        } catch (Exception e) {
            logger.warn("保存缓存失败: " + e.getMessage());
        }
        
        logger.info("✅ 批量更新完成! 成功蛋组=" + successGroups + ", 失败=" + failedGroups);
        logger.info("📊 共加载 " + totalPets + " 只宠物，分为 " + newGroupToPets.size() + " 个蛋组");
        logger.info("🧬 共加载 " + newEvolutionChains.size() + " 条进化链信息");
    }

    /**
     * 蛋组信息内部类
     */
    private static class EggGroupInfo {
        String groupName;
        List<String> pets;
        Map<String, String> evolutionChains; // 宠物名 -> 进化链
        
        EggGroupInfo(String groupName, List<String> pets) {
            this.groupName = groupName;
            this.pets = pets;
            this.evolutionChains = new HashMap<>();
        }
    }

    /**
     * 解析蛋组 API 响应（包含蛋组名称）
     * @return 蛋组信息（名称+宠物列表+进化链）
     */
    private EggGroupInfo parseEggGroupWithId(String jsonContent, int groupId) throws IOException {
        JsonNode root = objectMapper.readTree(jsonContent);
        
        if (!root.has("cards") || !root.get("cards").isArray()) {
            logger.warn("⚠️ 蛋组 " + groupId + " 响应格式错误");
            return null;
        }
        
        // 从 group 对象中提取蛋组名称
        String groupName = "未知组";
        if (root.has("group") && root.get("group").has("group_display")) {
            groupName = root.get("group").get("group_display").asText();
            logger.debug("📌 蛋组 " + groupId + " 名称: " + groupName);
        } else {
            logger.warn("⚠️ 无法获取蛋组 " + groupId + " 的名称，使用默认值");
        }
        
        List<String> petsInGroup = new ArrayList<>();
        Map<String, String> evolutionChains = new HashMap<>(); // 宠物 -> 进化链
        
        JsonNode cardsNode = root.get("cards");
        
        for (JsonNode card : cardsNode) {
            // 提取进化链中的所有宠物
            if (card.has("family_chain")) {
                String familyChain = card.get("family_chain").asText();
                // 分割进化链： "多西 → 库多西 → 波多西"
                String[] pets = familyChain.split("\\s*→\\s*|\\s+");
                
                for (String pet : pets) {
                    String trimmedPet = pet.trim();
                    if (!trimmedPet.isEmpty()) {
                        petsInGroup.add(trimmedPet);
                        evolutionChains.put(trimmedPet, familyChain);
                    }
                }
            } else if (card.has("representative") && card.get("representative").has("display_name")) {
                // 备用：如果没有 family_chain，使用 display_name
                String petName = card.get("representative").get("display_name").asText();
                petsInGroup.add(petName);
            }
        }
        
        if (petsInGroup.isEmpty()) {
            return null;
        }
        
        // 去重
        List<String> uniquePets = new ArrayList<>(new LinkedHashSet<>(petsInGroup));
        
        EggGroupInfo info = new EggGroupInfo(groupName, uniquePets);
        info.evolutionChains = evolutionChains;
        
        return info;
    }

    /**
     * 从 API 响应中提取蛋组名称
     */
    private String extractGroupNameFromResponse(JsonNode root, int defaultGroupId) {
        // 方案1：优先从第一个卡片的 class_name 提取
        if (root.has("cards") && root.get("cards").isArray()) {
            JsonNode firstCard = root.get("cards").get(0);
            if (firstCard.has("representative")) {
                JsonNode rep = firstCard.get("representative");
                
                if (rep.has("class_name")) {
                    String className = rep.get("class_name").asText();
                    logger.debug("📌 使用 class_name: " + className);
                    return className;
                }
                
                if (rep.has("type_name")) {
                    String typeName = rep.get("type_name").asText();
                    logger.debug("📌 使用 type_name: " + typeName);
                    return typeName;
                }
            }
        }
        
        // 方案2：检查顶层是否有 group_display 字段
        if (root.has("group_display")) {
            return root.get("group_display").asText();
        }
        
        if (root.has("egg_group_name")) {
            return root.get("egg_group_name").asText();
        }
        
        // 方案3：最后使用预定义的映射表
        logger.warn("⚠️ 无法从响应中提取蛋组名称，使用映射表");
        return getEggGroupNameById(defaultGroupId);
    }

    /**
     * 根据蛋组 ID 获取蛋组名称（映射表）
     */
    private String getEggGroupNameById(int groupId) {
        // 这里需要根据实际的蛋组名称建立映射
        // 以下是示例映射，请根据实际情况调整
        switch (groupId) {
            case 2: return "怪兽组";
            case 3: return "龙组";
            case 4: return "妖精组";
            case 5: return "植物组";
            case 6: return "飞行组";
            case 7: return "天空组";
            case 8: return "水中1组";
            case 9: return "水中2组";
            case 10: return "水中3组";
            case 11: return "虫组";
            case 12: return "恶魔组";
            case 13: return "矿物组";
            case 14: return "不定形组";
            case 15: return "百变怪组";
            default: return "蛋组" + groupId;
        }
    }

    /**
     * 解析蛋组 API 响应（旧版本，已废弃但保留兼容）
     * @return 该蛋组的宠物数量
     */
    private int parseEggGroupData(String jsonContent, int groupId, 
                                   Map<String, String> petToGroup, 
                                   Map<String, List<String>> groupToPets) throws IOException {
        EggGroupInfo info = parseEggGroupWithId(jsonContent, groupId);
        
        if (info == null || info.pets.isEmpty()) {
            return 0;
        }
        
        String groupName = info.groupName;
        List<String> petsInGroup = info.pets;
        
        for (String pet : petsInGroup) {
            String existingGroup = petToGroup.get(pet);
            if (existingGroup == null) {
                petToGroup.put(pet, groupName);
            } else if (!existingGroup.contains(groupName)) {
                petToGroup.put(pet, existingGroup + "," + groupName);
            }
        }
        
        groupToPets.put(groupName, petsInGroup);
        return petsInGroup.size();
    }

    /**
     * 从 classpath 资源文件加载宠物名字列表
     */
    private List<String> loadAllPetNames() {
        try {
            // 从 classpath 读取资源文件
            InputStream inputStream = getClass().getResourceAsStream(ALL_PETS_RESOURCE_PATH);
            
            if (inputStream == null) {
                logger.warn("宠物列表资源文件不存在: " + ALL_PETS_RESOURCE_PATH);
                return Collections.emptyList();
            }
            
            // 读取文件内容
            String json = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            inputStream.close();
            
            JsonNode root = objectMapper.readTree(json);
            
            List<String> petNames = new ArrayList<>();
            JsonNode petsNode = root.get("pets");
            
            if (petsNode != null && petsNode.isArray()) {
                for (JsonNode petNode : petsNode) {
                    String petName = petNode.asText().trim();
                    if (!petName.isEmpty()) {
                        petNames.add(petName);
                    }
                }
            }
            
            logger.info("📋 从资源文件加载了 " + petNames.size() + " 只宠物");
            return petNames;
        } catch (Exception e) {
            logger.error("读取宠物列表资源文件失败: " + e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 解析单个宠物的可配对宠物列表
     */
    private Set<String> parseBreedablePets(String jsonContent) throws IOException {
        JsonNode root = objectMapper.readTree(jsonContent);
        
        if (!root.has("breedable_pokemons")) {
            return Collections.emptySet();
        }
        
        Set<String> mates = new HashSet<>();
        JsonNode breedableNode = root.get("breedable_pokemons");
        
        if (breedableNode != null && breedableNode.isArray()) {
            for (JsonNode mateNode : breedableNode) {
                if (mateNode.has("name")) {
                    mates.add(mateNode.get("name").asText());
                }
            }
        }
        
        return mates;
    }

    /**
     * 根据配对关系构建蛋组
     * 算法：如果宠物A和B可以配对，它们属于同一个蛋组
     */
    private Map<String, Set<String>> buildEggGroups(Map<String, Set<String>> petToMatesMap) {
        Map<String, String> petToGroup = new HashMap<>(); // 宠物 -> 所属蛋组
        Map<String, Set<String>> groupToPets = new HashMap<>(); // 蛋组 -> 宠物集合
        int groupCounter = 0;
        
        for (Map.Entry<String, Set<String>> entry : petToMatesMap.entrySet()) {
            String pet = entry.getKey();
            Set<String> mates = entry.getValue();
            
            // 如果宠物已经有蛋组，将它的配对宠物也加入该蛋组
            String existingGroup = petToGroup.get(pet);
            
            if (existingGroup == null) {
                // 创建新蛋组
                String newGroup = "蛋组" + (++groupCounter);
                petToGroup.put(pet, newGroup);
                groupToPets.computeIfAbsent(newGroup, k -> new HashSet<>()).add(pet);
                existingGroup = newGroup;
            }
            
            // 将所有配对宠物加入同一个蛋组
            for (String mate : mates) {
                String mateGroup = petToGroup.get(mate);
                
                if (mateGroup == null) {
                    // 配对宠物还没有蛋组，加入当前蛋组
                    petToGroup.put(mate, existingGroup);
                    groupToPets.get(existingGroup).add(mate);
                } else if (!mateGroup.equals(existingGroup)) {
                    // 合并蛋组（因为这两个宠物可以配对）
                    mergeGroups(mateGroup, existingGroup, petToGroup, groupToPets);
                }
            }
        }
        
        return groupToPets;
    }

    /**
     * 合并两个蛋组（因为它们之间有交叉配对）
     */
    private void mergeGroups(String fromGroup, String toGroup, 
                             Map<String, String> petToGroup, 
                             Map<String, Set<String>> groupToPets) {
        Set<String> fromPets = groupToPets.get(fromGroup);
        if (fromPets == null || fromPets.isEmpty()) {
            return;
        }
        
        // 将所有宠物从 fromGroup 移动到 toGroup
        Set<String> toPets = groupToPets.computeIfAbsent(toGroup, k -> new HashSet<>());
        
        for (String pet : fromPets) {
            petToGroup.put(pet, toGroup);
            toPets.add(pet);
        }
        
        // 删除旧蛋组
        groupToPets.remove(fromGroup);
    }

    /**
     * 从 API 响应中提取蛋组名称（已废弃，保留兼容）
     */
    private String extractGroupName(JsonNode root) {
        // 这个方法不再使用，但保留以防其他地方调用
        if (root.has("searched_pokemon") && root.get("searched_pokemon").has("attributes")) {
            return root.get("searched_pokemon").get("attributes").asText();
        }
        return "未知组";
    }

    /**
     * 从 API 响应中提取同组的其他宠物（已废弃，逻辑已整合到 parseSinglePetData）
     */
    private void extractGroupMates(JsonNode root, String groupName, Map<String, List<String>> groupToPets) {
        // 这个方法不再需要，所有逻辑已在 parseSinglePetData 中处理
    }

    /**
     * 转换为缓存格式（用于保存）
     */
    private ObjectNode convertToCacheFormat(Map<String, String> petToGroup, Map<String, List<String>> groupToPets) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode groupsNode = MAPPER.createObjectNode();
        
        for (Map.Entry<String, List<String>> entry : groupToPets.entrySet()) {
            ArrayNode petsArray = MAPPER.createArrayNode();
            for (String pet : entry.getValue()) {
                petsArray.add(pet);
            }
            groupsNode.set(entry.getKey(), petsArray);
        }
        
        root.set("groups", groupsNode);
        return root;
    }

    /**
     * 核心更新逻辑：从远程 API 拉取数据
     */
    private void updateDataFromRemote() {
        logger.info("⚠️ 单次更新已废弃，请使用 batchUpdateFromRemote()");
    }

    // 模拟获取全量数据（请根据你的实际情况修改）
//    private String fetchFullDataMock() throws IOException {
//        // 实际场景：你可能需要请求一个包含所有宠物关系的接口
//        // 或者读取一个你定期上传到服务器的全量 JSON 文件
//        // 这里仅演示请求单个接口来验证逻辑
//        return fetchDataFromUrl(WIKI_API_URL + "粉星仔");
//    }

    private String fetchDataFromUrl(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        
        // 模拟浏览器请求头
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        conn.setRequestProperty("Referer", "https://wiki.lcx.cab/");
        conn.setRequestProperty("Connection", "keep-alive");

        if (conn.getResponseCode() == 200) {
            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();
            return response.toString();
        }
        return null;
    }

    /**
     * 解析 JSON 并原子性替换内存中的 Map
     */
    private void parseAndSaveData(String jsonContent) throws IOException {
        Map<String, String> newPetToGroup = new HashMap<>();
        Map<String, List<String>> newGroupToPets = new HashMap<>();

        // --- 解析逻辑开始 ---
        // 注意：这里需要根据 Wiki 返回的实际 JSON 结构来写
        // 假设返回结构是 { "data": [ {"pet": "粉星仔", "group": "妖精组"} ... ] }

        // 下面是伪代码，请根据实际 JSON 调整
        /*
        JsonNode root = objectMapper.readTree(jsonContent);
        if (root.has("data")) {
             for (JsonNode item : root.get("data")) {
                 String pet = item.get("pet").asText();
                 String group = item.get("group").asText();
                 newPetToGroup.put(pet, group);
                 newGroupToPets.computeIfAbsent(group, k -> new ArrayList<>()).add(pet);
             }
        }
        */

        // --- 演示用：手动填充数据 ---
        newPetToGroup.put("粉星仔", "妖精组");
        newGroupToPets.computeIfAbsent("妖精组", k -> new ArrayList<>()).add("粉星仔");
        // -----------------------

        // 原子替换：查询操作永远读到的是完整的新数据或旧数据，不会出现中间状态
        petToGroupMap.set(newPetToGroup);
        groupToPetsMap.set(newGroupToPets);
    }

    // --- 本地缓存读写 ---

    private boolean loadLocalData() {
        File file = new File(DATA_FILE_PATH);
        if (file.exists()) {
            try {
                String json = new String(Files.readAllBytes(Paths.get(DATA_FILE_PATH)), StandardCharsets.UTF_8);
                parseAndSaveData(json);
                logger.info("📂 已从本地文件加载数据");
                return true;
            } catch (Exception e) {
                logger.warn("读取本地缓存失败: " + e.getMessage());
                return false;
            }
        } else {
            logger.info("📂 本地数据文件不存在: " + file.getAbsolutePath());
            return false;
        }
    }

    /**
     * 初始化演示数据（硬编码，仅在无网络且无缓存时使用）
     */
    private void initializeDemoData() {
        Map<String, String> newPetToGroup = new HashMap<>();
        Map<String, List<String>> newGroupToPets = new HashMap<>();
        
        // 添加演示宠物
        newPetToGroup.put("粉星仔", "妖精组");
        newGroupToPets.computeIfAbsent("妖精组", k -> new ArrayList<>()).add("粉星仔");
        
        // 原子替换
        petToGroupMap.set(newPetToGroup);
        groupToPetsMap.set(newGroupToPets);
        
        logger.info("✅ 已加载演示数据：粉星仔 (妖精组)");
        
        // 尝试创建初始 JSON 文件
        try {
            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode dataArray = MAPPER.createArrayNode();
            
            ObjectNode demoPet = MAPPER.createObjectNode();
            demoPet.put("pet", "粉星仔");
            demoPet.put("group", "妖精组");
            dataArray.add(demoPet);
            
            root.set("data", dataArray);
            
            String initialJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            saveLocalData(initialJson);
            
            logger.info("💾 已创建初始数据文件: " + DATA_FILE_PATH);
        } catch (Exception e) {
            logger.warn("创建初始数据文件失败: " + e.getMessage());
        }
    }

    private void saveLocalData(String json) {
        try (FileWriter writer = new FileWriter(DATA_FILE_PATH)) {
            writer.write(json);
        } catch (IOException e) {
            logger.warn("保存本地缓存失败: " + e.getMessage());
        }
    }

    // --- 对外查询接口 (无网络请求) ---

    /**
     * 对外查询接口：获取同蛋组的所有宠物（仅返回最终形态）
     */
    public List<String> getMates(String petName) {
        String groups = petToGroupMap.get().get(petName);
        if (groups == null) return Collections.emptyList();
        
        // 一个宠物可能有多个蛋组，用逗号分隔
        String[] groupArray = groups.split(",");
        Set<String> allMates = new HashSet<>();
        
        for (String group : groupArray) {
            List<String> groupMates = groupToPetsMap.get().get(group.trim());
            if (groupMates != null) {
                for (String mate : groupMates) {
                    if (!mate.equals(petName)) { // 排除自己
                        allMates.add(mate);
                    }
                }
            }
        }
        
        // 过滤出最终形态
        return filterFinalForms(new ArrayList<>(allMates));
    }

    /**
     * 过滤出最终形态的宠物
     * 规则：如果宠物在进化链中，只保留链的最后一只
     */
    private List<String> filterFinalForms(List<String> mates) {
        Map<String, String> chains = evolutionChainsMap.get();
        Set<String> filtered = new HashSet<>();
        Set<String> excluded = new HashSet<>(); // 需要排除的非最终形态
        
        // 第一步：找出所有进化链中的宠物
        for (String mate : mates) {
            String chain = chains.get(mate);
            if (chain != null) {
                // 解析进化链，找出最终形态
                String[] forms = chain.split("\\s*→\\s*|\\s+");
                if (forms.length > 0) {
                    String finalForm = forms[forms.length - 1].trim();
                    filtered.add(finalForm);
                    
                    // 标记非最终形态为排除
                    for (int i = 0; i < forms.length - 1; i++) {
                        excluded.add(forms[i].trim());
                    }
                }
            } else {
                // 不在进化链中的宠物，直接保留
                filtered.add(mate);
            }
        }
        
        // 第二步：移除被排除的宠物
        filtered.removeAll(excluded);
        
        return new ArrayList<>(filtered);
    }

    /**
     * 根据身高体重预测可能的宠物种族
     * @param size 身高（米）
     * @param weight 体重（千克）
     * @return 预测结果列表，包含匹配的宠物信息
     */
    public List<PredictResult> predictEggRace(double size, double weight) {
        try {
            String url = String.format(EGG_PREDICT_API_URL, size, weight);
            String json = fetchDataFromUrl(url);
            
            if (json == null || json.isEmpty()) {
                logger.warn("⚠️ 预测 API 返回为空");
                return Collections.emptyList();
            }
            
            return parsePredictResult(json);
        } catch (Exception e) {
            logger.error("❌ 预测宠物种族失败: " + e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 解析预测 API 响应
     */
    private List<PredictResult> parsePredictResult(String jsonContent) throws IOException {
        JsonNode root = objectMapper.readTree(jsonContent);
        
        if (!root.has("success") || !root.get("success").asBoolean()) {
            logger.warn("⚠️ 预测 API 返回失败: " + root.path("message").asText());
            return Collections.emptyList();
        }
        
        List<PredictResult> results = new ArrayList<>();
        JsonNode pokemonsNode = root.get("pokemons");
        
        if (pokemonsNode != null && pokemonsNode.isArray()) {
            for (JsonNode pokemon : pokemonsNode) {
                String name = pokemon.has("name") ? pokemon.get("name").asText() : "未知";
                
                // 过滤恶意或无效数据
                if (isInvalidPetName(name)) {
                    logger.debug("⚠️ 过滤无效宠物名: " + name);
                    continue;
                }
                
                PredictResult result = new PredictResult();
                result.tId = pokemon.has("t_id") ? pokemon.get("t_id").asInt() : 0;
                result.name = name;
                result.attributes = pokemon.has("attributes") ? pokemon.get("attributes").asText() : "未知";
                result.chainGroup = pokemon.has("chain_group") ? pokemon.get("chain_group").asText() : "未知";
                result.evolutionStage = pokemon.has("evolution_stage") ? pokemon.get("evolution_stage").asInt() : 0;
                
                results.add(result);
            }
        }
        
        int totalMatches = root.has("total_matches") ? root.get("total_matches").asInt() : 0;
        
        logger.info("✅ 预测成功: 共找到 " + totalMatches + " 个匹配，返回前 " + results.size() + " 个");
        
        return results;
    }

    /**
     * 判断是否为无效的宠物名称
     */
    private boolean isInvalidPetName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return true;
        }
        
        String trimmed = name.trim();
        
        // 过滤明显的恶意关键词
        String[] invalidKeywords = {"傻逼", "傻叉", "废物", "垃圾", "操", "草泥马"};
        for (String keyword : invalidKeywords) {
            if (trimmed.contains(keyword)) {
                return true;
            }
        }
        
        // 过滤纯符号或无意义名称
        if (trimmed.matches("^[^\\u4e00-\\u9fa5a-zA-Z0-9]+$")) {
            return true;
        }
        
        return false;
    }

    /**
     * 预测结果内部类
     */
    public static class PredictResult {
        public int tId;              // 宠物 ID
        public String name;          // 宠物名称
        public String attributes;    // 属性
        public String chainGroup;    // 进化链组
        public int evolutionStage;   // 进化阶段
        
        @Override
        public String toString() {
            return "PredictResult{" +
                   "name='" + name + '\'' +
                   ", attributes='" + attributes + '\'' +
                   ", stage=" + evolutionStage +
                   '}';
        }
    }

    /**
     * 查询宠物的进化路径
     * @param petName 宠物名称
     * @return 进化路径字符串，如"多西 → 库多西 → 波多西"，null表示未找到或无进化
     */
    public String getEvolutionChain(String petName) {
        Map<String, String> chains = evolutionChainsMap.get();
        return chains.get(petName);
    }

    /**
     * 查询指定蛋组的所有宠物
     * @param groupName 蛋组名称
     * @return 该蛋组的所有宠物列表，null表示未找到
     */
    public List<String> getPetsInGroup(String groupName) {
        return groupToPetsMap.get().get(groupName);
    }

    /**
     * 按进化链分组宠物
     * @param pets 宠物列表
     * @return Map<进化链, 该链上的宠物列表>，无进化的宠物key为"无进化"
     */
    public Map<String, List<String>> groupByEvolution(List<String> pets) {
        Map<String, String> chains = evolutionChainsMap.get();
        Map<String, List<String>> result = new LinkedHashMap<>();
        Set<String> processed = new HashSet<>();
        
        // 第一步：找出所有有进化链的宠物并分组
        for (String pet : pets) {
            String chain = chains.get(pet);
            if (chain != null && !processed.contains(pet)) {
                // 这是一个新的进化链
                List<String> chainMembers = new ArrayList<>();
                
                // 找出该链上的所有宠物
                for (String p : pets) {
                    String c = chains.get(p);
                    if (chain.equals(c)) {
                        chainMembers.add(p);
                        processed.add(p);
                    }
                }
                
                result.put(chain, chainMembers);
            }
        }
        
        // 第二步：处理无进化的宠物
        List<String> noEvolution = new ArrayList<>();
        for (String pet : pets) {
            if (!processed.contains(pet)) {
                noEvolution.add(pet);
            }
        }
        
        if (!noEvolution.isEmpty()) {
            result.put("无进化", noEvolution);
        }
        
        return result;
    }

    /**
     * 查询宠物的蛋组名称
     * @param petName 宠物名称
     * @return 蛋组名称，可能包含多个（用逗号分隔），null表示未找到
     */
    public String getPetEggGroups(String petName) {
        return petToGroupMap.get().get(petName);
    }

    /**
     * 查询两只宠物是否可以生蛋
     * @param pet1 第一只宠物
     * @param pet2 第二只宠物
     * @return true如果可以生蛋，false如果不可以
     */
    public boolean canBreed(String pet1, String pet2) {
        String groups1 = petToGroupMap.get().get(pet1);
        String groups2 = petToGroupMap.get().get(pet2);
        
        if (groups1 == null || groups2 == null) {
            return false;
        }
        
        // 检查是否有共同的蛋组
        String[] groups1Array = groups1.split(",");
        String[] groups2Array = groups2.split(",");
        
        Set<String> set1 = new HashSet<>(Arrays.asList(groups1Array));
        Set<String> set2 = new HashSet<>(Arrays.asList(groups2Array));
        
        // 求交集
        set1.retainAll(set2);
        
        return !set1.isEmpty();
    }

    public String getGroupName(String petName) {
        return petToGroupMap.get().get(petName);
    }

    // 计算距离下一次指定时间（hour:minute）的毫秒数
    private long getDelayUntilNextRun(int hour, int minute) {
        Calendar now = Calendar.getInstance();
        Calendar nextRun = Calendar.getInstance();
        nextRun.set(Calendar.HOUR_OF_DAY, hour);
        nextRun.set(Calendar.MINUTE, minute);
        nextRun.set(Calendar.SECOND, 0);
        nextRun.set(Calendar.MILLISECOND, 0);

        if (now.after(nextRun)) {
            nextRun.add(Calendar.DAY_OF_YEAR, 1);
        }
        return nextRun.getTimeInMillis() - now.getTimeInMillis();
    }

    // 关闭调度器（在程序退出时调用）
    public void shutdown() {
        scheduler.shutdown();
    }
}