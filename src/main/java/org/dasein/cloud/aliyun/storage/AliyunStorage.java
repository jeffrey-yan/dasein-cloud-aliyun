/*
 *  *
 *  Copyright (C) 2009-2015 Dell, Inc.
 *  See annotations for authorship information
 *
 *  ====================================================================
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *  ====================================================================
 *
 */

package org.dasein.cloud.aliyun.storage;

import org.dasein.cloud.aliyun.Aliyun;
import org.dasein.cloud.storage.AbstractStorageServices;
import org.dasein.cloud.storage.BlobStoreSupport;
import org.dasein.cloud.storage.StorageServices;

import javax.annotation.Nullable;

/**
 * Created by Jeffrey Yan on 7/7/2015.
 *
 * @author Jeffrey Yan
 * @since 2015.05.1
 */
public class AliyunStorage extends AbstractStorageServices<Aliyun> implements StorageServices{
    public AliyunStorage(Aliyun provider) {
        super(provider);
    }

    @Override
    public @Nullable BlobStoreSupport getOnlineStorageSupport() {
        return new AliyunBlobStore(getProvider());
    }
}
