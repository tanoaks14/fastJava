package com.fastjava.http.response;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class HeaderStorageTest {

    @Test
    public void growsBeyondInitialCapacity() {
        HeaderStorage storage = new HeaderStorage(2);
        for (int i = 0; i < 10; i++) {
            storage.add("X-Test-" + i, "v" + i);
        }

        assertEquals(10, storage.size());
        for (int i = 0; i < 10; i++) {
            HeaderStorage.HeaderValue header = storage.get(i);
            assertNotNull(header);
            assertEquals("X-Test-" + i, header.name());
            assertEquals("v" + i, header.value());
        }
    }
}