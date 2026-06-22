package com.start.vision;

import java.awt.image.BufferedImage;

@FunctionalInterface
public interface ImageTemplate<T> {
    BufferedImage render(Object data);
}
