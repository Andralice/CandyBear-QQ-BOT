package com.start.vision;

import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * 横版职业战力卡片。
 */
public class ProfessionCardTemplate implements ImageTemplate<ProfessionData> {

    private static final int W = 1920;
    private static final int H = 1080;
    private static final Color BG = new Color(0x171A18);
    private static final Color SEAL_BACKGROUND = new Color(0x151614);
    private static final Color BORDER = new Color(0x404A43);
    private static final Color TEXT = new Color(0xF4F6F7);
    private static final Color SUBTEXT = new Color(0xBAC4BD);
    private static final Color MUTED = new Color(0x95A39B);
    private static final Color BAR_BG = new Color(0x303B34);
    private static final Color[] TIER_COLORS = {
            new Color(0xD0D6DC),
            new Color(0x54C9B0),
            new Color(0x70AAFF),
            new Color(0xC78AFF),
            new Color(0xF2C35F)
    };
    private static final String[] TIER_LABELS = {"普通", "普通", "稀有", "史诗", "传说"};
    private static final String[] TIER_MATERIALS = {"冷银", "青碧", "宝蓝", "紫晶", "鎏金"};
    private static final String[] SEAL_RESOURCES = {
            "assets/rank-seals/rank-1-common-silver.png",
            "assets/rank-seals/rank-2-common-jade.png",
            "assets/rank-seals/rank-3-rare-sapphire.png",
            "assets/rank-seals/rank-4-epic-amethyst.png",
            "assets/rank-seals/rank-5-legendary-gold.png"
    };
    private static final BufferedImage[] SEALS = new BufferedImage[5];
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final int SEAL_CACHE_SIZE = 384;
    private static final int POWER_BAR_X = 84;
    private static final int POWER_BAR_Y = 744;
    private static final int POWER_BAR_W = 1042;

    private final Font fLabel;
    private final Font fBody;
    private final Font fTitle;
    private final Font fHero;
    private final Font fNumber;
    private final Font fSmall;
    private final Font fTiny;

    public ProfessionCardTemplate() {
        ImageRenderer fonts = ImageRenderer.getInstance();
        fLabel = fonts.loadFont("HarmonyOS_SansSC_Bold.ttf", 31f);
        fBody = fonts.loadFont("HarmonyOS_SansSC_Medium.ttf", 34f);
        fTitle = fonts.loadFont("HarmonyOS_SansSC_Bold.ttf", 32f);
        fHero = fonts.loadFont("HarmonyOS_SansSC_Black.ttf", 104f);
        fNumber = fonts.loadFont("HarmonyOS_SansSC_Bold.ttf", 214f);
        fSmall = fonts.loadFont("HarmonyOS_SansSC_Medium.ttf", 24f);
        fTiny = fonts.loadFont("HarmonyOS_SansSC_Medium.ttf", 20f);
    }

    @Override
    public BufferedImage render(Object data) {
        if (!(data instanceof ProfessionData p)) throw new IllegalArgumentException("数据格式错误");

        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setColor(BG);
            g.fillRect(0, 0, W, H);

            Color tierColor = tierColor(p.tier);
            g.setColor(tierColor);
            g.fillRect(0, 0, W, 8);
            g.setColor(BORDER);
            g.setStroke(new BasicStroke(2f));
            g.drawRoundRect(24, 24, W - 48, H - 48, 40, 40);

            drawHeader(g, p);
            drawProfession(g, p, tierColor);
            drawPower(g, p, tierColor);
            drawSealPanel(g, p, tierColor);
            drawTierLegend(g);
            drawFooter(g, p);
        } finally {
            g.dispose();
        }
        return image;
    }

    private void drawHeader(Graphics2D g, ProfessionData p) {
        g.setColor(tierColor(p.tier));
        g.fillRoundRect(76, 58, 8, 42, 8, 8);
        g.setFont(fTitle);
        g.setColor(TEXT);
        g.drawString("天命 · 职业战力档案", 108, 91);

        String meta = LocalDate.now().format(DATE_FORMAT);
        if (p.groupRank > 0) meta += "  /  本群第 " + p.groupRank + " 名";
        drawRightFit(g, meta, fSmall, 18f, 1840, 90, 570, SUBTEXT);
        g.setColor(BORDER);
        g.setStroke(new BasicStroke(2f));
        g.drawLine(76, 126, 1844, 126);
    }

    private void drawProfession(Graphics2D g, ProfessionData p, Color tierColor) {
        g.setFont(fLabel);
        g.setColor(SUBTEXT);
        g.drawString("职业名称", 84, 207);

        String professionName = safe(p.professionName, "未知职业");
        drawFit(g, professionName, fHero, 60f, 1030, 80, 330, TEXT);

        g.setColor(new Color(0x424B45));
        g.setStroke(new BasicStroke(2f));
        g.drawLine(84, 359, 520, 359);
        g.setColor(tierColor);
        g.setStroke(new BasicStroke(5f));
        g.drawLine(84, 359, 236, 359);

        String path = p.professionPath == null || p.professionPath.isBlank()
                ? "" : p.professionPath + "脉";
        if (!path.isEmpty()) {
            g.setFont(fBody);
            g.setColor(tierColor);
            g.drawString(path, 84, 420);
        }
        g.setColor(new Color(0x515B54));
        g.setStroke(new BasicStroke(2f));
        g.drawLine(184, 389, 184, 432);

        String tierName = safe(p.tierName, "修行者");
        if (!tierName.startsWith(p.tier + "阶")) tierName = p.tier + "阶 · " + tierName;
        drawFit(g, tierName, fSmall, 20f, 430, 216, 420, TEXT);
        drawFit(g, singleLine(p.changeDesc), fTiny, 15f, 520, 610, 420, MUTED);

        if (p.bestTier > 0) {
            String bestName = safe(p.bestTierName, p.bestTier + "阶");
            drawFit(g, "历史巅峰 · " + bestName, fTiny, 16f, 900, 84, 464, MUTED);
        }
    }

    private void drawPower(Graphics2D g, ProfessionData p, Color tierColor) {
        drawPowerIcon(g, 84, 481, tierColor);
        g.setFont(fBody);
        g.setColor(SUBTEXT);
        g.drawString("战力值", 140, 512);

        String power = formatNumber(p.combatPower);
        drawFit(g, power, fNumber, 96f, 600, 78, 704, tierColor);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 48));
        g.setColor(new Color(0x94A29A));
        g.drawString("/ 10,000", 692, 694);

        double ratio = Math.max(0, Math.min(1, p.combatPower / 10000.0));
        drawRightFit(g, String.format(Locale.ROOT, "%.1f%%", ratio * 100),
                new Font(Font.SANS_SERIF, Font.BOLD, 49), 36f, 1126, 646, 300, tierColor);
        drawRightFit(g, "战力进度", fTiny, 18f, 1126, 681, 280, SUBTEXT);

        g.setColor(BAR_BG);
        g.fillRoundRect(POWER_BAR_X, POWER_BAR_Y, POWER_BAR_W, 20, 20, 20);
        int fillWidth = (int) (POWER_BAR_W * ratio);
        if (fillWidth > 0) {
            g.setPaint(new GradientPaint(POWER_BAR_X, POWER_BAR_Y, tierColor,
                    POWER_BAR_X + Math.max(1, fillWidth), POWER_BAR_Y, brighter(tierColor, 0.42f)));
            g.fillRoundRect(POWER_BAR_X, POWER_BAR_Y, fillWidth, 20, 20, 20);
            int markerX = POWER_BAR_X + fillWidth;
            Path2D marker = new Path2D.Float();
            marker.moveTo(markerX, 731);
            marker.lineTo(markerX + 14, 744);
            marker.lineTo(markerX, 777);
            marker.lineTo(markerX - 14, 744);
            marker.closePath();
            g.setColor(brighter(tierColor, 0.52f));
            g.fill(marker);
        }

        g.setFont(fTiny);
        g.setColor(MUTED);
        g.drawString("0", POWER_BAR_X, 798);
        drawCentered(g, "5,000", fTiny, MUTED, POWER_BAR_X + POWER_BAR_W / 2, 798, 120);
        drawRightFit(g, "10,000", fTiny, 17f, POWER_BAR_X + POWER_BAR_W, 798, 130, MUTED);

        String description = truncate(g, singleLine(p.description), fTiny, 1042);
        if (!description.isEmpty()) {
            g.setFont(fTiny);
            g.setColor(MUTED);
            g.drawString(description, 84, 820);
        }
    }

    private void drawSealPanel(Graphics2D g, ProfessionData p, Color tierColor) {
        g.setColor(SEAL_BACKGROUND);
        g.fillRoundRect(1212, 158, 632, 604, 16, 16);
        g.setColor(BORDER);
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(1212, 158, 632, 604, 16, 16);
        g.setColor(BORDER);
        g.setStroke(new BasicStroke(2f));
        g.drawLine(1234, 181, 1820, 181);

        int tileX = 1340;
        int tileY = 190;
        g.setColor(SEAL_BACKGROUND);
        g.fillRoundRect(tileX, tileY, 376, 376, 14, 14);

        BufferedImage seal = sealImage(p.tier);
        if (seal != null) {
            g.drawImage(seal, tileX + 8, tileY + 8, 360, 360, null);
        } else {
            drawFallbackSeal(g, tileX + 8, tileY + 8, 360, tierColor);
        }

        String rarity = safe(p.rarity, TIER_LABELS[tierIndex(p.tier)]);
        drawCentered(g, rarity, fLabel.deriveFont(82f), tierColor, 1528, 641, 576);
        drawCentered(g, p.tier + "阶品质", fSmall.deriveFont(27f), SUBTEXT, 1528, 690, 480);
        g.setColor(BORDER);
        g.setStroke(new BasicStroke(2f));
        g.drawLine(1320, 720, 1736, 720);
    }

    private void drawTierLegend(Graphics2D g) {
        g.setColor(BORDER);
        g.setStroke(new BasicStroke(2f));
        g.drawLine(76, 827, 1844, 827);
        g.setFont(fLabel);
        g.setColor(new Color(0xE1E7E3));
        g.drawString("五阶品质", 82, 886);
        g.setFont(fTiny);
        g.setColor(MUTED);
        g.drawString("色彩与印章", 82, 923);

        for (int i = 0; i < SEAL_RESOURCES.length; i++) {
            int tier = i + 1;
            int iconX = 316 + i * 299;
            int labelX = iconX + 128;
            BufferedImage seal = sealImage(tier);
            if (seal != null) {
                g.drawImage(seal, iconX, 854, 112, 112, null);
            } else {
                drawFallbackSeal(g, iconX, 854, 112, TIER_COLORS[i]);
            }

            Color color = TIER_COLORS[i];
            drawFit(g, tier + "阶 · " + TIER_LABELS[i], fSmall.deriveFont(29f),
                    20f, 240, labelX, 891, color);
            g.setFont(fSmall);
            g.setColor(MUTED);
            g.drawString(TIER_MATERIALS[i], labelX, 932);
        }
    }

    private void drawFooter(Graphics2D g, ProfessionData p) {
        g.setColor(BORDER);
        g.setStroke(new BasicStroke(2f));
        g.drawLine(76, 990, 1844, 990);
        drawFit(g, "USER #" + safe(p.userId, "unknown"), fTiny, 16f, 800, 82, 1024, MUTED);
    }

    private void drawPowerIcon(Graphics2D g, int x, int y, Color tierColor) {
        g.setColor(mix(BG, tierColor, 0.24f));
        g.fillRoundRect(x, y, 38, 38, 10, 10);
        Path2D bolt = new Path2D.Float();
        bolt.moveTo(x + 22, y + 5);
        bolt.lineTo(x + 10, y + 21);
        bolt.lineTo(x + 19, y + 21);
        bolt.lineTo(x + 15, y + 33);
        bolt.lineTo(x + 29, y + 16);
        bolt.lineTo(x + 20, y + 16);
        bolt.closePath();
        g.setColor(brighter(tierColor, 0.28f));
        g.fill(bolt);
    }

    private static BufferedImage sealImage(int tier) {
        int index = tierIndex(tier);
        BufferedImage image = SEALS[index];
        if (image != null) return image;

        synchronized (SEALS) {
            image = SEALS[index];
            if (image != null) return image;
            try (InputStream input = ProfessionCardTemplate.class.getClassLoader()
                    .getResourceAsStream(SEAL_RESOURCES[index])) {
                if (input == null) return null;
                BufferedImage source = ImageIO.read(input);
                if (source == null) return null;

                image = new BufferedImage(SEAL_CACHE_SIZE, SEAL_CACHE_SIZE, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics();
                try {
                    g.setColor(BG);
                    g.fillRect(0, 0, SEAL_CACHE_SIZE, SEAL_CACHE_SIZE);
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g.drawImage(source, 0, 0, SEAL_CACHE_SIZE, SEAL_CACHE_SIZE, null);
                } finally {
                    g.dispose();
                }
                SEALS[index] = image;
                return image;
            } catch (IOException e) {
                return null;
            }
        }
    }

    private static void drawFallbackSeal(Graphics2D g, int x, int y, int size, Color color) {
        g.setColor(withAlpha(color, 110));
        g.setStroke(new BasicStroke(Math.max(2f, size / 70f)));
        g.drawOval(x + size / 12, y + size / 12, size * 5 / 6, size * 5 / 6);
        g.drawOval(x + size / 5, y + size / 5, size * 3 / 5, size * 3 / 5);
        g.setColor(color);
        g.setFont(new Font(Font.SERIF, Font.BOLD, size / 2));
        drawCentered(g, "✦", g.getFont(), color, x + size / 2, y + size * 3 / 5, size);
    }

    private static int tierIndex(int tier) {
        return Math.max(0, Math.min(TIER_COLORS.length - 1, tier - 1));
    }

    private static Color tierColor(int tier) {
        return TIER_COLORS[tierIndex(tier)];
    }

    private static Font fittedFont(Graphics2D g, String text, Font base, int maxWidth, float minSize) {
        Font font = base;
        while (font.getSize2D() > minSize
                && g.getFontMetrics(font).stringWidth(text) > maxWidth) {
            font = font.deriveFont(Math.max(minSize, font.getSize2D() - 2f));
        }
        return font;
    }

    private static void drawFit(Graphics2D g, String text, Font base, float minSize,
                                int maxWidth, int x, int y, Color color) {
        if (text == null || text.isEmpty()) return;
        Font font = fittedFont(g, text, base, maxWidth, minSize);
        g.setFont(font);
        g.setColor(color);
        g.drawString(text, x, y);
    }

    private static void drawRightFit(Graphics2D g, String text, Font base, float minSize,
                                     int rightX, int y, int maxWidth, Color color) {
        if (text == null || text.isEmpty()) return;
        Font font = fittedFont(g, text, base, maxWidth, minSize);
        g.setFont(font);
        g.setColor(color);
        g.drawString(text, rightX - g.getFontMetrics(font).stringWidth(text), y);
    }

    private static void drawCentered(Graphics2D g, String text, Font font, Color color,
                                     int centerX, int y, int maxWidth) {
        if (text == null || text.isEmpty()) return;
        Font fitted = fittedFont(g, text, font, maxWidth, 18f);
        g.setFont(fitted);
        g.setColor(color);
        g.drawString(text, centerX - g.getFontMetrics(fitted).stringWidth(text) / 2, y);
    }

    private static String truncate(Graphics2D g, String text, Font font, int maxWidth) {
        if (text == null || text.isEmpty()) return "";
        g.setFont(font);
        FontMetrics metrics = g.getFontMetrics();
        if (metrics.stringWidth(text) <= maxWidth) return text;
        String suffix = "…";
        int end = text.length();
        while (end > 0 && metrics.stringWidth(text.substring(0, end) + suffix) > maxWidth) end--;
        return end == 0 ? suffix : text.substring(0, end) + suffix;
    }

    private static String singleLine(String text) {
        return text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String safe(String text, String fallback) {
        return text == null || text.isBlank() ? fallback : text;
    }

    private static String formatNumber(int value) {
        return String.format(Locale.US, "%,d", value);
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private static Color mix(Color a, Color b, float weight) {
        float inverse = 1f - weight;
        return new Color(
                (int) (a.getRed() * inverse + b.getRed() * weight),
                (int) (a.getGreen() * inverse + b.getGreen() * weight),
                (int) (a.getBlue() * inverse + b.getBlue() * weight));
    }

    private static Color brighter(Color color, float amount) {
        return mix(color, Color.WHITE, amount);
    }
}
