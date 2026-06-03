package com.start.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.repository.UserAliasRepository;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 天气查询工具。直接使用 Open-Meteo 地理编码（支持中文/拼音），
 * 不再维护静态城市映射表。
 *
 * 地点优先级：primary_location > secondary_location > 询问用户
 */
public class WeatherTool implements Tool {
    private static final HttpClient http = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();
    private final UserAliasRepository aliasRepo;

    public WeatherTool(UserAliasRepository aliasRepo) { this.aliasRepo = aliasRepo; }
    public WeatherTool() { this.aliasRepo = new UserAliasRepository(); }

    @Override public String getName() { return "get_weather"; }

    @Override
    public String getDescription() {
        return "查询天气。用户明确说城市→直接用；用户没指定→city填UNKNOWN，系统自动用主地点。days默认1，最多7天预报。绝不要自己猜城市。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "city", Map.of("type", "string", "description", "城市名（中文或拼音），未指定填 UNKNOWN"),
                        "user_id", Map.of("type", "string", "description", "当前用户 ID，用于查记忆中的地点"),
                        "days", Map.of("type", "string", "description", "预报天数，默认1，最多7")
                ),
                "required", Arrays.asList("city"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String city = (String) args.get("city");
        String userId = (String) args.get("user_id");
        int days = parseIntSafe((String) args.get("days"), 1);
        if (days < 1) days = 1;
        if (days > 7) days = 7;

        if (city == null || city.trim().isEmpty() || "UNKNOWN".equalsIgnoreCase(city.trim())) {
            if (userId != null && !userId.isEmpty()) {
                Optional<String> loc = aliasRepo.getLocation(userId, (String) args.getOrDefault("group_id", "0"));
                if (loc.isPresent()) { city = loc.get(); }
                else { return "我不知道你在哪个城市，可以告诉我吗？比如'我在北京'"; }
            } else { return "我不知道你在哪个城市，可以告诉我吗？"; }
        }

        String originalCity = city.trim();

        // 查完后写入 secondary_location
        if (userId != null && !userId.isEmpty() && !"UNKNOWN".equalsIgnoreCase(originalCity)) {
            aliasRepo.updateLocation(userId, (String) args.getOrDefault("group_id", "0"), originalCity, false);
        }

        try {
            // 尝试多个搜索关键词（去掉区/县/市后缀，加省份前缀等）
            String[] searchNames = {originalCity};
            if (originalCity.endsWith("区") || originalCity.endsWith("县") || originalCity.endsWith("市")) {
                searchNames = new String[]{originalCity, originalCity.substring(0, originalCity.length() - 1)};
            }
            // 注：如果用户之前设置过所在地，可能带了省份信息在上下文中，这里保持简单

            JsonNode geo = null;
            for (String name : searchNames) {
                String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);
                String geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=" + encoded + "&count=1&language=zh";
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(geoUrl))
                        .timeout(java.time.Duration.ofSeconds(10)).build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    geo = mapper.readTree(resp.body());
                    if (geo.has("results") && geo.get("results").size() > 0) break;
                    // 不带 language=zh 重试
                    geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=" + encoded + "&count=1";
                    req = HttpRequest.newBuilder().uri(URI.create(geoUrl))
                            .timeout(java.time.Duration.ofSeconds(10)).build();
                    resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                    geo = mapper.readTree(resp.body());
                    if (geo.has("results") && geo.get("results").size() > 0) break;
                }
            }

            if (geo == null || !geo.has("results") || geo.get("results").size() == 0) {
                return "未找到城市 [" + originalCity + "]，试试换个写法（如去掉'区''县'）？";
            }

            JsonNode r = geo.get("results").get(0);
            double lat = r.get("latitude").asDouble();
            double lon = r.get("longitude").asDouble();
            String foundName = r.has("name") ? r.get("name").asText() : originalCity;

            String wUrl = String.format(
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                    "&current=temperature_2m,weather_code,relative_humidity_2m,wind_speed_10m" +
                    "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code" +
                    "&timezone=auto&forecast_days=" + days, lat, lon);

            HttpRequest wReq = HttpRequest.newBuilder().uri(URI.create(wUrl))
                    .timeout(java.time.Duration.ofSeconds(10)).build();
            HttpResponse<String> wResp = http.send(wReq, HttpResponse.BodyHandlers.ofString());
            if (wResp.statusCode() != 200) return "天气服务暂不可用。";

            JsonNode w = mapper.readTree(wResp.body());
            JsonNode cur = w.path("current");
            if (cur.isMissingNode()) return "未获取到当前天气。";

            double t = cur.path("temperature_2m").asDouble();
            int wc = cur.path("weather_code").asInt();
            int h = cur.path("relative_humidity_2m").asInt();
            double ws = cur.path("wind_speed_10m").asDouble();

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("📍%s 当前：%s，%.0f°C，湿度%d%%，风速%.1fkm/h",
                    foundName, wmoDesc(wc), t, h, ws));

            JsonNode daily = w.path("daily");
            if (!daily.isMissingNode() && daily.path("time").isArray() && daily.path("time").size() > 0) {
                int dayCount = daily.path("time").size();
                String[] dayLabels = {"今日","明日","后天","大后天"};
                for (int i = 0; i < dayCount; i++) {
                    double hi = daily.path("temperature_2m_max").get(i).asDouble();
                    double lo = daily.path("temperature_2m_min").get(i).asDouble();
                    int rp = daily.path("precipitation_probability_max").get(i).asInt();
                    int dc = daily.path("weather_code").get(i).asInt();
                    String label = i < dayLabels.length ? dayLabels[i] : daily.path("time").get(i).asText().substring(5);
                    sb.append(String.format(" | %s：%s，%.0f~%.0f°C，降雨%d%%",
                            label, wmoDesc(dc), lo, hi, rp));
                }
            }
            return sb.toString();

        } catch (IOException | InterruptedException e) {
            return "网络请求失败，稍后再试。";
        } catch (Exception e) {
            return "解析天气数据出错。";
        }
    }

    private int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private String wmoDesc(int c) {
        return switch (c) {
            case 0 -> "晴"; case 1,2,3 -> "多云"; case 45,48 -> "雾";
            case 51,53,55 -> "毛毛雨"; case 56,57 -> "冻毛毛雨"; case 61,63,65 -> "小到中雨";
            case 66,67 -> "冻雨"; case 71,73,75 -> "小到中雪"; case 77 -> "雪粒";
            case 80,81,82 -> "阵雨"; case 85,86 -> "阵雪"; case 95 -> "雷暴"; case 96,99 -> "雷暴伴冰雹";
            default -> "未知天气";
        };
    }
}
