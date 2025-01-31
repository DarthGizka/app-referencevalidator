/*
 * Copyright (c) 2023 gematik GmbH
 * 
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an 'AS IS' BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.gematik.refv;

import lombok.AllArgsConstructor;

import java.util.Optional;

@AllArgsConstructor
public enum SupportedValidationModule {
    ERP("erp"),
    EAU("eau"),
    ISIP1("isip1"),
    ISIK2("isik2"),
    ISIK1("isik1"),
    DIGA("diga");


    private final String name;

    @Override
    public String toString() {
        return name;
    }

    public static Optional<SupportedValidationModule> fromString(String name) {
        for (SupportedValidationModule b : SupportedValidationModule.values()) {
            if (b.toString().equalsIgnoreCase(name)) {
                return Optional.of(b);
            }
        }
        return Optional.empty();
    }
}
