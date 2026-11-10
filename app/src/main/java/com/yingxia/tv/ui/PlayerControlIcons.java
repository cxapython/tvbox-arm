package com.yingxia.tv.ui;

import com.yingxia.tv.R;

/** Explicit resource mapping for the seek controls in the TV player layout. */
public final class PlayerControlIcons {
  private PlayerControlIcons() {}

  public static int resource(boolean forward) {
    return forward ? R.drawable.tv_ic_fast_forward : R.drawable.tv_ic_rewind;
  }

  public static int viewId(boolean forward) {
    return forward ? androidx.media3.ui.R.id.exo_ffwd : androidx.media3.ui.R.id.exo_rew;
  }
}
