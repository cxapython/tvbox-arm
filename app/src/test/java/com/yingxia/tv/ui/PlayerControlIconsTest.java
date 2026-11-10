package com.yingxia.tv.ui;

import static org.junit.Assert.assertEquals;

import com.yingxia.tv.R;
import org.junit.Test;

public final class PlayerControlIconsTest {
  @Test
  public void seekDirectionsUseDedicatedVectorResourcesAndViews() {
    assertEquals(R.drawable.tv_ic_rewind, PlayerControlIcons.resource(false));
    assertEquals(R.drawable.tv_ic_fast_forward, PlayerControlIcons.resource(true));
    assertEquals(androidx.media3.ui.R.id.exo_rew, PlayerControlIcons.viewId(false));
    assertEquals(androidx.media3.ui.R.id.exo_ffwd, PlayerControlIcons.viewId(true));
  }
}
