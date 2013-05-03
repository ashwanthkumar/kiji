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
package org.kiji.rest.resources;

import static org.kiji.rest.RoutesConstants.ENTITY_ID_PATH;
import static org.kiji.rest.RoutesConstants.INSTANCE_PARAMETER;
import static org.kiji.rest.RoutesConstants.TABLE_PARAMETER;

import java.util.List;
import java.util.Set;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response.Status;

import com.google.common.collect.Lists;
import com.yammer.metrics.annotation.Timed;

import org.apache.commons.codec.binary.Hex;

import org.kiji.schema.EntityId;
import org.kiji.schema.EntityIdFactory;
import org.kiji.schema.KijiTable;
import org.kiji.schema.KijiURI;
import org.kiji.schema.avro.RowKeyComponent;
import org.kiji.schema.avro.RowKeyFormat;
import org.kiji.schema.avro.RowKeyFormat2;
import org.kiji.schema.layout.KijiTableLayout;
/**
 * This REST resource represents an entity_id in Kiji.
 *
 * This resource is served for requests using the resource identifiers: <li>
 * GET /v1/instances/&lt;instance&gt/tables/&lt;table&gt/entityId;
 */
@Path(ENTITY_ID_PATH)
@Produces(MediaType.APPLICATION_JSON)
public class EntityIdResource extends AbstractKijiResource {

  /**
   * Default constructor.
   *
   * @param cluster KijiURI in which these instances are contained.
   * @param instances The list of accessible instances.
   */
  public EntityIdResource(KijiURI cluster, Set<KijiURI> instances) {
    super(cluster, instances);
  }

  /**
   * GETs a hexadecimal EntityId using the components specified in the query.
   *
   * @param instance in which the table resides.
   * @param table to translate the entityId for.
   * @param components of the entityId.
   * @return a message containing a list of available sub-resources.
   */
  @GET
  @Timed
  public String getEntityId(@PathParam(INSTANCE_PARAMETER) String instance,
      @PathParam(TABLE_PARAMETER) String table,
      @QueryParam("component") List<String> components) {

    if (components.isEmpty()) {
      throw new WebApplicationException(new IllegalArgumentException("Components Required "
          + "to construct entity id!"), Status.BAD_REQUEST);
    }

    KijiTable kijiTable = getKijiTable(instance, table);
    KijiTableLayout layout = kijiTable.getLayout();
    final EntityIdFactory factory = EntityIdFactory.getFactory(layout);
    final Object keysFormat = layout.getDesc().getKeysFormat();
    EntityId entityId = null;

    if (keysFormat instanceof RowKeyFormat) {
      entityId = factory.getEntityId(components.get(0));
    } else {
      //Go through the components and convert the component string into
      //the strong type that is desired by the key.
      RowKeyFormat2 newFormat = (RowKeyFormat2) keysFormat;
      List<RowKeyComponent> rkComponents = newFormat.getComponents();
      List<Object> parsedComponents = Lists.newArrayList();
      for (int i = 0; i < components.size() && i < rkComponents.size(); i++) {
        RowKeyComponent comp = rkComponents.get(i);

        switch (comp.getType()) {
        case INTEGER: {
          parsedComponents.add(Integer.parseInt(components.get(i)));
          break;
        }
        case LONG: {
          parsedComponents.add(Long.parseLong(components.get(i)));
          break;
        }
        default:
          parsedComponents.add(components.get(i));
        }
      }
      entityId = factory.getEntityId(parsedComponents);
    }
    return new String(Hex.encodeHex(entityId.getHBaseRowKey()));
  }

}
