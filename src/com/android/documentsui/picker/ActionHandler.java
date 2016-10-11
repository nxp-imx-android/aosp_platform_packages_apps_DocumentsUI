/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.documentsui.picker;

import static com.android.documentsui.base.Shared.DEBUG;
import static com.android.documentsui.base.State.ACTION_GET_CONTENT;
import static com.android.documentsui.base.State.ACTION_PICK_COPY_DESTINATION;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import com.android.documentsui.AbstractActionHandler;
import com.android.documentsui.ActivityConfig;
import com.android.documentsui.DocumentsAccess;
import com.android.documentsui.Metrics;
import com.android.documentsui.base.DocumentInfo;
import com.android.documentsui.base.DocumentStack;
import com.android.documentsui.base.EventListener;
import com.android.documentsui.base.Lookup;
import com.android.documentsui.base.MimeTypes;
import com.android.documentsui.base.RootInfo;
import com.android.documentsui.base.Shared;
import com.android.documentsui.base.State;
import com.android.documentsui.dirlist.DocumentDetails;
import com.android.documentsui.dirlist.Model;
import com.android.documentsui.dirlist.Model.Update;
import com.android.documentsui.picker.ActionHandler.Addons;
import com.android.documentsui.roots.RootsAccess;
import com.android.documentsui.selection.SelectionManager;

import java.util.concurrent.Executor;

import javax.annotation.Nullable;

/**
 * Provides {@link PickActivity} action specializations to fragments.
 */
class ActionHandler<T extends Activity & Addons> extends AbstractActionHandler<T> {

    private static final String TAG = "PickerActionHandler";

    private final ActivityConfig mConfig;
    private final ContentScope mScope;

    ActionHandler(
            T activity,
            State state,
            RootsAccess roots,
            DocumentsAccess docs,
            SelectionManager selectionMgr,
            Lookup<String, Executor> executors,
            ActivityConfig activityConfig) {

        super(activity, state, roots, docs, selectionMgr, executors);

        mConfig = activityConfig;
        mScope = new ContentScope(this::onModelLoaded);
    }

    @Override
    public void initLocation(Intent intent) {
        if (mState.restored) {
            if (DEBUG) Log.d(TAG, "Stack already resolved");
        } else {
            // We set the activity title in AsyncTask.onPostExecute().
            // To prevent talkback from reading aloud the default title, we clear it here.
            mActivity.setTitle("");

            // As a matter of policy we don't load the last used stack for the copy
            // destination picker (user is already in Files app).
            // Concensus was that the experice was too confusing.
            // In all other cases, where the user is visiting us from another app
            // we restore the stack as last used from that app.
            if (Shared.ACTION_PICK_COPY_DESTINATION.equals(intent.getAction())) {
                if (DEBUG) Log.d(TAG, "Launching directly into Home directory.");
                loadHomeDir();
            } else {
                if (DEBUG) Log.d(TAG, "Attempting to load last used stack for calling package.");
                new LoadLastAccessedStackTask<>(mActivity, mState, mRoots).execute();
            }
        }
    }

    @Override
    public void showAppDetails(ResolveInfo info) {
        final Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", info.activityInfo.packageName, null));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
        mActivity.startActivity(intent);
    }

    @Override
    public void openInNewWindow(DocumentStack path) {
        // Open new window support only depends on vanilla Activity, so it is
        // implemented in our parent class. But we don't support that in
        // picking. So as a matter of defensiveness, we override that here.
        throw new UnsupportedOperationException("Can't open in new window");
    }

    @Override
    public void openRoot(RootInfo root) {
        Metrics.logRootVisited(mActivity, root);
        mActivity.onRootPicked(root);
    }

    @Override
    public void openRoot(ResolveInfo info) {
        Metrics.logAppVisited(mActivity, info);
        mActivity.onAppPicked(info);
    }

    @Override
    public boolean viewDocument(DocumentDetails details) {
        return openDocument(details);
    }

    @Override
    public boolean openDocument(DocumentDetails details) {
        DocumentInfo doc = mScope.model.getDocument(details.getModelId());
        if (doc == null) {
            Log.w(TAG,
                    "Can't view item. No Document available for modeId: " + details.getModelId());
            return false;
        }

        if (mConfig.isDocumentEnabled(doc.mimeType, doc.flags, mState)) {
            mActivity.onDocumentPicked(doc);
            mSelectionMgr.clearSelection();
            return true;
        }
        return false;
    }

    private void onModelLoaded(Model.Update update) {
        boolean showDrawer = false;

        if (MimeTypes.mimeMatches(MimeTypes.VISUAL_MIMES, mState.acceptMimes)) {
            showDrawer = false;
        }
        if (mState.external && mState.action == ACTION_GET_CONTENT) {
            showDrawer = true;
        }
        if (mState.action == ACTION_PICK_COPY_DESTINATION) {
            showDrawer = true;
        }

        // When launched into empty root, open drawer.
        if (mScope.model.isEmpty()) {
            showDrawer = true;
        }

        if (showDrawer && !mState.hasInitialLocationChanged() && !mScope.searchMode
                && !mScope.modelLoadObserved) {
            // This noops on layouts without drawer, so no need to guard.
            mActivity.setRootsDrawerOpen(true);
        }

        mScope.modelLoadObserved = true;
    }

    ActionHandler<T> reset(Model model, boolean searchMode) {
        assert(model != null);

        mScope.model = model;
        mScope.searchMode = searchMode;

        model.addUpdateListener(mScope.modelUpdateListener);
        return this;
    }

    private static final class ContentScope {

        @Nullable Model model;
        boolean searchMode;

        // We use this to keep track of whether a model has been previously loaded or not so we can
        // open the drawer on empty directories on first launch
        private boolean modelLoadObserved;

        private final EventListener<Update> modelUpdateListener;

        public ContentScope(EventListener<Update> modelUpdateListener) {
            this.modelUpdateListener = modelUpdateListener;
        }
    }

    public interface Addons extends CommonAddons {
        void onAppPicked(ResolveInfo info);
        void onDocumentPicked(DocumentInfo doc);
    }
}
