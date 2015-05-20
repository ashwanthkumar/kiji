/**
 * (c) Copyright 2013 WibiData, Inc.
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
package org.kiji.scoring;

import org.kiji.annotations.ApiAudience;
import org.kiji.annotations.ApiStability;
import org.kiji.annotations.Inheritance;
import org.kiji.schema.KijiDataRequest;

/**
 * Interface defining operations available to {@link org.kiji.scoring.KijiFreshnessPolicy} and
 * {@link org.kiji.scoring.ScoreFunction} per-request methods.
 *
 * <p>
 *   Also extends {@link FreshenerGetStoresContext}.
 * </p>
 */
@ApiAudience.Public
@ApiStability.Experimental
@Inheritance.Sealed
public interface FreshenerContext extends FreshenerSetupContext {

  /**
   * Get the KijiDataRequest issued by the client which triggered the Freshener serviced by this
   * context.
   *
   * @return the KijiDataRequest issued by the client which triggered the Freshener serviced by this
   *     context.
   */
  KijiDataRequest getClientRequest();
}
