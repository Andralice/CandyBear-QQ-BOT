package com.start.repository;

import com.start.model.UserProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class UserProfileRepositoryTest {

    @Test
    void legacyFreeTextOverwriteIsDisabled() {
        UserProfile profile = new UserProfile();
        profile.setUserId("10001");

        assertThrows(UnsupportedOperationException.class,
                () -> new UserProfileRepository(null).saveOrUpdate(profile));
    }
}
