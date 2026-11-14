package ru.nexora.client;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NexoraSkinAtlasTest {
    @Test
    public void everyPartDefinesSixValidFacesInsideTheAtlas() {
        for (NexoraSkinAtlas.Part part : NexoraSkinAtlas.Part.values()) {
            for (NexoraSkinAtlas.Face face : NexoraSkinAtlas.Face.values()) {
                NexoraSkinAtlas.UvRect rect = NexoraSkinAtlas.rect(part, face);
                assertTrue(rect.x0() >= 0 && rect.y0() >= 0);
                assertTrue(rect.x1() <= 64 && rect.y1() <= 64);
                assertTrue(rect.x1() > rect.x0());
                assertTrue(rect.y1() > rect.y0());
                assertTrue(rect.u0() >= 0f && rect.v0() >= 0f);
                assertTrue(rect.u1() <= 1f && rect.v1() <= 1f);
            }
        }
    }

    @Test
    public void headFrontUsesExpectedEightByEightPixels() {
        NexoraSkinAtlas.UvRect front =
                NexoraSkinAtlas.rect(NexoraSkinAtlas.Part.HEAD, NexoraSkinAtlas.Face.FRONT);
        assertEquals(8, front.x0());
        assertEquals(8, front.y0());
        assertEquals(16, front.x1());
        assertEquals(16, front.y1());
    }

    @Test
    public void leftLimbsUseLowerAtlasRows() {
        NexoraSkinAtlas.UvRect leg =
                NexoraSkinAtlas.rect(NexoraSkinAtlas.Part.LEFT_LEG, NexoraSkinAtlas.Face.FRONT);
        NexoraSkinAtlas.UvRect arm =
                NexoraSkinAtlas.rect(NexoraSkinAtlas.Part.LEFT_ARM, NexoraSkinAtlas.Face.FRONT);
        assertEquals(52, leg.y0());
        assertEquals(64, leg.y1());
        assertEquals(52, arm.y0());
        assertEquals(64, arm.y1());
    }

    @Test
    public void rightLimbsUseUpperAtlasRows() {
        NexoraSkinAtlas.UvRect leg =
                NexoraSkinAtlas.rect(NexoraSkinAtlas.Part.RIGHT_LEG, NexoraSkinAtlas.Face.FRONT);
        NexoraSkinAtlas.UvRect arm =
                NexoraSkinAtlas.rect(NexoraSkinAtlas.Part.RIGHT_ARM, NexoraSkinAtlas.Face.FRONT);
        assertEquals(20, leg.y0());
        assertEquals(32, leg.y1());
        assertEquals(20, arm.y0());
        assertEquals(32, arm.y1());
    }
}
