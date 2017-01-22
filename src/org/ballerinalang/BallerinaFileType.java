/*
 *  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
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
 */

package org.ballerinalang;

import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.*;

import javax.swing.*;

public class BallerinaFileType extends LanguageFileType {
    public static final BallerinaFileType INSTANCE = new BallerinaFileType();

    private BallerinaFileType() {
        super(BallerinaLanguage.INSTANCE);
    }

    @NotNull
    @Override
    public String getName() {
        return "Ballerina file";
    }

    @NotNull
    @Override
    public String getDescription() {
        return "Ballerina language file";
    }

    @NotNull
    @Override
    public String getDefaultExtension() {
        return "bal";
    }

    @Nullable
    @Override
    public Icon getIcon() {
        return BallerinaIcons.FILE;
    }
}
