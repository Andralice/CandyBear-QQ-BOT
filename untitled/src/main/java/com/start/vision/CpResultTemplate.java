package com.start.vision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

public class CpResultTemplate implements ImageTemplate<CpResultData> {

    private static final Logger logger = LoggerFactory.getLogger(CpResultTemplate.class);
    private final ImageRenderer renderer;
    private final Font titleFont;
    private final Font nameFont;
    private final Font heartFont;
    private static final String BACKGROUND_IMAGE_RESOURCE = "assets/bg/back3.jpg"; // 注意：没有 src/main/resources！
    // 头像相关常量
    private static final int AVATAR_SIZE = 200;
    private static final int AVATAR_X = 50;
    private static final int AVATAR_Y = 80;
    private static final int TEXT_MARGIN_LEFT = AVATAR_X + AVATAR_SIZE + 40; // 文本起始X坐标

    public CpResultTemplate() {
        this.renderer = ImageRenderer.getInstance();
        this.titleFont = renderer.loadFont("HarmonyOS_SansSC_Medium.ttf", 32f);
        this.nameFont = renderer.loadFont("HarmonyOS_SansSC_Bold.ttf", 44f);
        this.heartFont = renderer.loadFont("HarmonyOS_SansSC_Regular.ttf", 36f);
    }

    @Override
    public BufferedImage render(Object data) {
        if (!(data instanceof CpResultData)) {
            throw new IllegalArgumentException("Expected CpResultData, got: " +
                    (data == null ? "null" : data.getClass().getName()));
        }
        CpResultData cpData = (CpResultData) data;

        int width = 800;
        int height = 500;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();

        // 加载并绘制底图
        try (InputStream is = ImageRenderer.class.getClassLoader().getResourceAsStream(BACKGROUND_IMAGE_RESOURCE)) {
            if (is == null) {
                logger.debug("背景图片未找到: " + BACKGROUND_IMAGE_RESOURCE);
                // 可选：绘制纯色背景代替
                g.setColor(Color.GRAY);
                g.fillRect(0, 0, width, height);
            } else {
                logger.debug("加载背景图片: " + BACKGROUND_IMAGE_RESOURCE);
                BufferedImage backgroundImage = ImageIO.read(is);
                backgroundImage = ImageUtils.resize(backgroundImage, width, height);
                g.drawImage(backgroundImage, 0, 0, null);
            }
        } catch (IOException e) {
            System.err.println("Failed to load background image: " + e.getMessage());
            e.printStackTrace();
        }

        // 抗锯齿设置
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

        // 白色背景
//        g.setColor(Color.WHITE);
//        g.fillRect(0, 0, width, height);

        // 绘制头像（用户B）
        String avatarUrl = cpData.getAvatarB();
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            BufferedImage avatar = ImageUtils.downloadImage(avatarUrl);
            if (avatar != null) {
                // 缩放为正方形
                avatar = ImageUtils.resize(avatar, AVATAR_SIZE, AVATAR_SIZE);

                // 添加阴影（层叠效果）
                g.setColor(new Color(0, 0, 0, 100));
                g.fillRoundRect(AVATAR_X + 5, AVATAR_Y + 5, AVATAR_SIZE, AVATAR_SIZE, 20, 20);

                // 绘制头像
                g.drawImage(avatar, AVATAR_X, AVATAR_Y, null);

                // 白色边框
                g.setColor(Color.WHITE);
                g.drawRoundRect(AVATAR_X, AVATAR_Y, AVATAR_SIZE, AVATAR_SIZE, 20, 20);
            }
        }

        // 标题：今日天命CP（黑色，左对齐在 TEXT_MARGIN_LEFT）
        g.setFont(titleFont);
        g.setColor(Color.BLACK);
        g.drawString("今日天命CP", TEXT_MARGIN_LEFT, AVATAR_Y + 40);

        // 用户A
        g.setFont(nameFont);
        g.setColor(new Color(70, 50, 110));
        g.drawString(cpData.getUserA(), TEXT_MARGIN_LEFT, AVATAR_Y + 90);

        // 爱心
        g.setFont(heartFont);
        g.setColor(new Color(220, 60, 100));
        g.drawString("❤️", TEXT_MARGIN_LEFT, AVATAR_Y + 160);

        // 用户B
        g.setFont(nameFont);
        g.setColor(new Color(70, 50, 110));
        g.drawString(cpData.getUserB(), TEXT_MARGIN_LEFT, AVATAR_Y + 230);

        // 底部祝福语（居中）
        g.setFont(renderer.loadFont("HarmonyOS_Sans_SC_Regular.ttf", 20f));
        g.setColor(new Color(150, 130, 170));
        renderer.drawCenteredString(g, "愿你们今天甜甜蜜蜜～", width, height - 40);

        g.dispose();
        return img;
    }
}