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

package org.kiji.schema.impl;

import java.util.ArrayList;
import java.util.List;

import com.google.common.base.Preconditions;

import org.kiji.annotations.ApiAudience;
import org.kiji.schema.EntityId;

/** Implements the raw row key format. */
@ApiAudience.Private
public final class RawEntityId extends EntityId {
  /**
   * Creates a RawEntityId from the specified Kiji row key.
   *
   * @param kijiRowKey Kiji row key.
   * @return a new RawEntityId with the specified Kiji row key.
   */
  public static RawEntityId getEntityId(byte[] kijiRowKey) {
    return new RawEntityId(kijiRowKey);
  }

  /**
   * Creates a RawEntityId from the specified HBase row key.
   *
   * @param hbaseRowKey HBase row key.
   * @return a new RawEntityId with the specified HBase row key.
   */
  public static RawEntityId fromHBaseRowKey(byte[] hbaseRowKey) {
    return new RawEntityId(hbaseRowKey);
  }

  /**
   * Kiji row key bytes.
   * This is also the HBase row key bytes, as the RAW format uses the identity transform.
   */
  private final byte[] mBytes;

  /**
   * Creates a raw entity ID.
   *
   * @param rowKey Kiji/HBase row key (both row keys are identical).
   */
  private RawEntityId(byte[] rowKey) {
    mBytes = Preconditions.checkNotNull(rowKey);
  }

  /** {@inheritDoc} */
  @Override
  public byte[] getHBaseRowKey() {
    return mBytes;
  }

  /** {@inheritDoc} **/
  @Override
  @SuppressWarnings("unchecked")
  public <T> T getComponentByIndex(int idx) {
    Preconditions.checkArgument(idx == 0);
    return (T)mBytes.clone();
  }

  /** {@inheritDoc} **/
  @Override
  public List<Object> getComponents() {
    List<Object> resp = new ArrayList<Object>();
    resp.add(mBytes);
    return resp;
  }
}
