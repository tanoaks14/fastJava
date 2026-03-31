package com.fastjava.http.response;

import org.junit.Test;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ResponseBufferPoolTest {

    @Test
    public void acquireReleaseReusesSameThreadBuffer() {
        byte[] first = ResponseBufferPool.acquire();
        ResponseBufferPool.release(first);
        byte[] second = ResponseBufferPool.acquire();

        assertSame("Expected same thread-local buffer to be reused", first, second);
        ResponseBufferPool.release(second);
    }

    @Test
    public void reentrantAcquireGetsFreshBuffer() {
        byte[] first = ResponseBufferPool.acquire();
        byte[] second = ResponseBufferPool.acquire();

        assertNotSame("Expected re-entrant acquire to allocate a fresh buffer", first, second);
        ResponseBufferPool.release(first);
        ResponseBufferPool.release(second);
    }

    @Test
    public void utilizationReflectsCheckoutState() {
        byte[] buffer = ResponseBufferPool.acquire();
        assertTrue("Expected utilization to indicate checked-out state",
                ResponseBufferPool.getPoolUtilization() >= 1.0);
        ResponseBufferPool.release(buffer);
    }
}