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
        return "查询天气。用户明确说城市→直接用；用户没指定→city填UNKNOWN，系统自动用主地点。绝不要自己猜城市。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "city", Map.of("type", "string", "description", "城市名（中文或拼音），未指定填 UNKNOWN"),
                        "user_id", Map.of("type", "string", "description", "当前用户 ID，用于查记忆中的地点")
                ),
                "required", Arrays.asList("city"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String city = (String) args.get("city");
        String userId = (String) args.get("user_id");

        if (city == null || city.trim().isEmpty() || "UNKNOWN".equalsIgnoreCase(city.trim())) {
            if (userId != null && !userId.isEmpty()) {
                Optional<String> loc = aliasRepo.getLocation(userId);
                if (loc.isPresent()) { city = loc.get(); }
                else { return "我不知道你在哪个城市，可以告诉我吗？比如'我在北京'"; }
            } else { return "我不知道你在哪个城市，可以告诉我吗？"; }
        }

        String originalCity = city.trim();

        // 查完后写入 secondary_location
        if (userId != null && !userId.isEmpty() && !"UNKNOWN".equalsIgnoreCase(originalCity)) {
            aliasRepo.updateLocation(userId, originalCity, false);
        }

        try {
            String encoded = URLEncoder.encode(originalCity, StandardCharsets.UTF_8);
            String geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=" + encoded + "&count=1&language=zh";

            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(geoUrl))
                    .timeout(java.time.Duration.ofSeconds(10)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return "无法连接地理服务，稍后再试。";

            JsonNode geo = mapper.readTree(resp.body());
            if (!geo.has("results") || geo.get("results").size() == 0) {
                // 去掉 language=zh 重试
                geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=" + encoded + "&count=1";
                req = HttpRequest.newBuilder().uri(URI.create(geoUrl))
                        .timeout(java.time.Duration.ofSeconds(10)).build();
                resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                geo = mapper.readTree(resp.body());
                if (!geo.has("results") || geo.get("results").size() == 0) {
                    return "未找到城市 [" + originalCity + "]，请确认名称。";
                }
            }

            JsonNode r = geo.get("results").get(0);
            double lat = r.get("latitude").asDouble();
            double lon = r.get("longitude").asDouble();
            String foundName = r.has("name") ? r.get("name").asText() : originalCity;

            String wUrl = String.format(
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                    "&current=temperature_2m,weather_code,relative_humidity_2m,wind_speed_10m" +
                    "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code" +
                    "&timezone=auto&forecast_days=1", lat, lon);

            req = HttpRequest.newBuilder().uri(URI.create(wUrl))
                    .timeout(java.time.Duration.ofSeconds(10)).build();
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return "天气服务暂不可用。";

            JsonNode w = mapper.readTree(resp.body());
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
                double hi = daily.path("temperature_2m_max").get(0).asDouble();
                double lo = daily.path("temperature_2m_min").get(0).asDouble();
                int rp = daily.path("precipitation_probability_max").get(0).asInt();
                int dc = daily.path("weather_code").get(0).asInt();
                sb.append(String.format(" | 今日：%s，%.0f~%.0f°C，降雨概率%d%%",
                        wmoDesc(dc), lo, hi, rp));
            }
            return sb.toString();

        } catch (IOException | InterruptedException e) {
            return "网络请求失败，稍后再试。";
        } catch (Exception e) {
            return "解析天气数据出错。";
        }
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
