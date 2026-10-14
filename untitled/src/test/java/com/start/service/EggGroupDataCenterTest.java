package com.start.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EggGroupDataCenterTest {
    @Test
    void disabledInstanceDoesNotLoadOrRefreshGameData() {
        EggGroupDataCenter dataCenter = new EggGroupDataCenter(false);
        assertTrue(dataCenter.getMates("粉星仔").isEmpty());
    }
}
