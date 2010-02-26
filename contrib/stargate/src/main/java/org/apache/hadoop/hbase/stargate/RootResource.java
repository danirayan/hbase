/*
 * Copyright 2010 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.stargate;

import java.io.IOException;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.core.Response.ResponseBuilder;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.stargate.auth.User;
import org.apache.hadoop.hbase.stargate.model.TableListModel;
import org.apache.hadoop.hbase.stargate.model.TableModel;

@Path("/")
public class RootResource implements Constants {
  private static final Log LOG = LogFactory.getLog(RootResource.class);

  RESTServlet servlet;
  CacheControl cacheControl;

  public RootResource() throws IOException {
    servlet = RESTServlet.getInstance();
    cacheControl = new CacheControl();
    cacheControl.setNoCache(true);
    cacheControl.setNoTransform(false);
  }

  TableListModel getTableList() throws IOException {
    TableListModel tableList = new TableListModel();
    HBaseAdmin admin = new HBaseAdmin(servlet.getConfiguration());
    HTableDescriptor[] list = admin.listTables();
    for (HTableDescriptor htd: list) {
      tableList.add(new TableModel(htd.getNameAsString()));
    }
    return tableList;
  }

  TableListModel getTableListForUser(User user) throws IOException {
    TableListModel tableList;
    if (user.isAdmin()) {
      tableList = getTableList();
    } else {
      tableList = new TableListModel();
      HBaseAdmin admin = new HBaseAdmin(servlet.getConfiguration());
      HTableDescriptor[] list = admin.listTables();
      String prefix = user.getName() + ".";
      for (HTableDescriptor htd: list) {
        String name = htd.getNameAsString();
        if (!name.startsWith(prefix)) {
          continue;
        }
        tableList.add(new TableModel(name.substring(prefix.length())));
      }
    }
    return tableList;
  }

  @GET
  @Produces({MIMETYPE_TEXT, MIMETYPE_XML, MIMETYPE_JSON, MIMETYPE_PROTOBUF})
  public Response get(@Context UriInfo uriInfo) throws IOException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("GET " + uriInfo.getAbsolutePath());
    }
    if (servlet.isMultiUser()) {
      throw new WebApplicationException(Response.Status.BAD_REQUEST);
    }
    try {
      ResponseBuilder response = Response.ok(getTableList());
      response.cacheControl(cacheControl);
      return response.build();
    } catch (IOException e) {
      throw new WebApplicationException(e, 
                  Response.Status.SERVICE_UNAVAILABLE);
    }
  }

  @Path("status/cluster")
  public StorageClusterStatusResource getClusterStatusResource() 
      throws IOException {
    if (servlet.isMultiUser()) {
      throw new WebApplicationException(Response.Status.BAD_REQUEST);
    }
    return new StorageClusterStatusResource();
  }

  @Path("version")
  public VersionResource getVersionResource() {
    return new VersionResource();
  }

  @Path("{token: [0-9a-fA-F]{32} }") // 128 bit md5 sums
  public Response getTableRootResource(
      @PathParam("token") String token) throws IOException {
    if (servlet.isMultiUser()) {
      User user = servlet.getAuthenticator().getUserForToken(token);
      if (user == null || user.isDisabled()) {
        throw new WebApplicationException(Response.Status.FORBIDDEN);
      }
      try {
        ResponseBuilder response = Response.ok(getTableListForUser(user));
        response.cacheControl(cacheControl);
        return response.build();
      } catch (IOException e) {
        throw new WebApplicationException(e, 
                    Response.Status.SERVICE_UNAVAILABLE);
      }
    }
    throw new WebApplicationException(Response.Status.BAD_REQUEST);
  }

  @Path("{token: [0-9a-fA-F]{32} }/status/cluster") // 128 bit md5 sums
  public StorageClusterStatusResource getClusterStatusResourceAuthorized(
      @PathParam("token") String token) throws IOException {
    if (servlet.isMultiUser()) {
      User user = servlet.getAuthenticator().getUserForToken(token);
      if (user != null && user.isAdmin() && !user.isDisabled()) {
        return new StorageClusterStatusResource();
      }
      throw new WebApplicationException(Response.Status.FORBIDDEN);
    }
    throw new WebApplicationException(Response.Status.BAD_REQUEST);
  }

  @Path("{token: [0-9a-fA-F]{32} }/{table}")
  public TableResource getTableResource(@PathParam("token") String token, 
      @PathParam("table") String table) throws IOException {
    if (servlet.isMultiUser()) {
      User user = servlet.getAuthenticator().getUserForToken(token);
      if (user == null || user.isDisabled()) {
        throw new WebApplicationException(Response.Status.FORBIDDEN);
      }
      return new TableResource(user, table);
    }
    throw new WebApplicationException(Response.Status.BAD_REQUEST);
  }

  @Path("{table}")
  public TableResource getTableResource(@PathParam("table") String table)
    throws IOException {
    if (servlet.isMultiUser()) {
      throw new WebApplicationException(Response.Status.BAD_REQUEST);
    }
    return new TableResource(null, table);
  }

}