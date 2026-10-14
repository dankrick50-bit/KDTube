package com.liskovsoft.smartyoutubetv2.tv.util;

import android.content.Context;
import android.os.Build;

import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator;
import com.bumptech.glide.module.AppGlideModule;
import com.liskovsoft.sharedutils.helpers.DeviceHelpers;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

/**
 * https://bumptech.github.io/glide/doc/configuration.html#disk-cache<br/>
 * https://stackoverflow.com/questions/46108915/how-to-increase-the-cache-size-in-glide-android
 */
@GlideModule
public class GlideCachingModule extends AppGlideModule {
    private final static long CACHE_SIZE = 10 * 1024 * 1024; // 10 MB
    private final static long LOW_RAM_BYTES = 1_500_000_000L;
    private final static long MEDIUM_RAM_BYTES = 3_500_000_000L;

    @Override
    public void applyOptions(Context context, GlideBuilder builder) {
        //if (MyApplication.from(context).isTest())
        //    return; // NOTE: StatFs will crash on robolectric.

        // Limit cache size
        builder.setDiskCache(new InternalCacheDiskCacheFactory(context, CACHE_SIZE));

        long deviceRam = DeviceHelpers.getDeviceRam(context);

        // By default Glide keeps 2 screens of bitmaps in the memory cache and,
        // before Android 8, 4 more in the bitmap pool. That's ~50 MB for 1080p, on the java heap.
        // Its own low memory mode is for the devices that are marked as such by the vendor only.
        if (!Utils.isEnoughRam() || (deviceRam > 0 && deviceRam <= LOW_RAM_BYTES)) {
            // Low ram device (sold as "1 GB")
            applyMemoryLimits(context, builder, 1, 1);
        } else if (deviceRam > 0 && deviceRam <= MEDIUM_RAM_BYTES) {
            // Sold as "2 GB" or "3 GB". The cache is the same, the pool is twice smaller.
            applyMemoryLimits(context, builder, 2, 2);
        }
    }

    private static void applyMemoryLimits(Context context, GlideBuilder builder, int memoryCacheScreens, int bitmapPoolScreens) {
        MemorySizeCalculator.Builder calculator = new MemorySizeCalculator.Builder(context)
                .setMemoryCacheScreens(memoryCacheScreens);

        // NOTE: Android 8 and above: the bitmaps are outside of the java heap and the pool is 1 screen already
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            calculator.setBitmapPoolScreens(bitmapPoolScreens);
        }

        builder.setMemorySizeCalculator(calculator);
    }
}
