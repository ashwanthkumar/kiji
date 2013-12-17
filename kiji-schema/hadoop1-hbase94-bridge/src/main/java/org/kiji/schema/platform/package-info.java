/**
 * (c) Copyright 2012 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Hadoop 1.x and HBase 0.94.x-backed provider of the KijiSchema PlatformBridge API.
 * This bridge handles the following Hadoop / HBase distributions:
 * <ul>
 *    <li>Apache Hadoop1.x with HBase 0.94.2+</li>
 *    <li>MapR's distribution of HBase</li>
 * </ul>
 */
package org.kiji.schema.platform;
