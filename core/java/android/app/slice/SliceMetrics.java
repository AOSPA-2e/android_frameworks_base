/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app.slice;

import android.annotation.NonNull;
import android.content.Context;
import android.net.Uri;

import com.android.internal.logging.MetricsLogger;

/**
 * Metrics interface for slices.
 *
 * This is called by SliceView, so Slice develoers should
 * not need to reference this class.
 *
 * @see androidx.slice.widget.SliceView
 */
public class SliceMetrics {

    private static final String TAG = "SliceMetrics";
    private MetricsLogger mMetricsLogger;

    /**
     * An object to be used throughout the life of a slice to register events.
     */
    public SliceMetrics(@NonNull Context context, @NonNull Uri uri) {
        mMetricsLogger = new MetricsLogger();
    }

    /**
     * To be called whenever the slice becomes visible to the user.
     */
    public void logVisible() {
    }

    /**
     * To be called whenever the slice becomes invisible to the user.
     */
    public void logHidden() {
    }

    /**
     * To be called whenever the use interacts with a slice.
     *@param subSlice The URI of the sub-slice that is the subject of the interaction.
     */
    public void logTouch(@NonNull Uri subSlice) {
    }
}
