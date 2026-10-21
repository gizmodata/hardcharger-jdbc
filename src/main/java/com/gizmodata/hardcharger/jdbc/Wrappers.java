package com.gizmodata.hardcharger.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

/**
 * The Warp SQL driver's {@link Connection} and {@link DatabaseMetaData}, as Hardcharger's: the
 * metadata names this driver and its version and the {@code jdbc:hardcharger:} URL, and reads
 * indexes from Hardcharger's DuckDB catalogs. Other answers come from Warp SQL. {@code unwrap}
 * reaches the Warp SQL classes.
 */
final class Wrappers {
  private Wrappers() {}

  static Connection connection(Connection inner, String url) {
    return (Connection)
        Proxy.newProxyInstance(
            Wrappers.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            new ConnectionHandler(inner, url));
  }

  private abstract static class Handler implements InvocationHandler {
    final Object inner;

    Handler(Object inner) {
      this.inner = inner;
    }

    /** The answer for a method this wrapper changes, or {@code this} to pass it on. */
    abstract Object own(Object proxy, Method method, Object[] args) throws Exception;

    @Override
    public final Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      switch (method.getName()) {
        case "equals":
          if (args != null && args.length == 1) {
            return proxy == args[0];
          }
          break;
        case "hashCode":
          if (args == null) {
            return System.identityHashCode(proxy);
          }
          break;
        case "toString":
          if (args == null) {
            return "Hardcharger[" + inner + "]";
          }
          break;
        case "unwrap":
          if (args != null && args.length == 1 && args[0] instanceof Class<?> type) {
            return type.isInstance(proxy) ? proxy : invokeInner(method, args);
          }
          break;
        case "isWrapperFor":
          if (args != null && args.length == 1 && args[0] instanceof Class<?> type) {
            return type.isInstance(proxy) || (Boolean) invokeInner(method, args);
          }
          break;
        default:
          break;
      }
      Object answer = own(proxy, method, args);
      return answer == this ? invokeInner(method, args) : answer;
    }

    private Object invokeInner(Method method, Object[] args) throws Throwable {
      try {
        return method.invoke(inner, args);
      } catch (InvocationTargetException e) {
        throw e.getCause();
      }
    }
  }

  private static final class ConnectionHandler extends Handler {
    private final String url;

    ConnectionHandler(Connection inner, String url) {
      super(inner);
      this.url = url;
    }

    @Override
    Object own(Object proxy, Method method, Object[] args) throws Exception {
      if ("getMetaData".equals(method.getName()) && args == null) {
        DatabaseMetaData metaData = ((Connection) inner).getMetaData();
        return Proxy.newProxyInstance(
            Wrappers.class.getClassLoader(),
            new Class<?>[] {DatabaseMetaData.class},
            new MetaDataHandler(metaData, (Connection) proxy, url));
      }
      return this;
    }
  }

  private static final class MetaDataHandler extends Handler {
    private final Connection connection;
    private final String url;

    MetaDataHandler(DatabaseMetaData inner, Connection connection, String url) {
      super(inner);
      this.connection = connection;
      this.url = url;
    }

    @Override
    Object own(Object proxy, Method method, Object[] args) throws Exception {
      if (args != null) {
        if ("getIndexInfo".equals(method.getName())) {
          return IndexMetadata.read(
              connection, (String) args[0], (String) args[1], (String) args[2], (Boolean) args[3]);
        }
        return this;
      }
      switch (method.getName()) {
        case "getDriverName":
          return HardchargerDriver.NAME;
        case "getDriverVersion":
          return HardchargerDriver.version();
        case "getDriverMajorVersion":
          return HardchargerDriver.versionPart(0);
        case "getDriverMinorVersion":
          return HardchargerDriver.versionPart(1);
        case "getURL":
          return HardchargerUrl.redact(url);
        case "getConnection":
          return connection;
        default:
          return this;
      }
    }
  }
}
