package com.github.mustachejava;

import com.github.mustachejava.codes.PartialCode;
import com.github.mustachejava.util.GuardException;
import com.github.mustachejava.util.Wrapper;

import java.io.IOException;
import java.io.Writer;
import java.util.logging.Logger;

/**
 * Simplest possible code implementaion with some default shared behavior
 */
public class Code {
  private StringBuilder sb = new StringBuilder();
  protected String appended;

  protected final ObjectHandler oh;
  protected final String name;
  protected TemplateContext tc;
  protected final Code mustache;
  protected final String type;

  // Callsite caching
  protected Wrapper wrapper;

  // Initialization
  protected boolean inited;

  // Debug callsites
  private static boolean debug = Boolean.getBoolean("mustache.debug");
  protected Logger logger = Logger.getLogger("mustache");

  // TODO: Recursion protection. Need better guard logic. But still fast.
  protected boolean notfound = false;
  protected boolean returnThis = false;

  public Code() {
    this(null, null, null, null, null);
  }

  public Code(TemplateContext tc, ObjectHandler oh, Code mustache, String name, String type) {
    this.oh = oh;
    this.mustache = mustache;
    this.type = type;
    this.name = name;
    this.tc = tc;
    if (".".equals(name)) {
      returnThis = true;
    }
  }

  public Code[] getCodes() {
    return mustache == null ? null : mustache.getCodes();
  }

  public synchronized void init() {
    Code[] codes = getCodes();
    if (codes != null) {
      for (Code code : codes) {
        code.init();
      }
    }
  }

  public void setCodes(Code[] newcodes) {
    mustache.setCodes(newcodes);
  }

  /**
   * Retrieve the first value in the stacks of scopes that matches
   * the give name. The method wrapper is cached and guarded against
   * the type or number of scopes changing. We should deepen the guard.
   * <p/>
   * Methods will be found using the object handler, called here with
   * another lookup on a guard failure and finally coerced to a final
   * value based on the ObjectHandler you provide.
   *
   * @param name   The common name of the field or method
   * @param scopes An array of scopes to interrogate from right to left.
   * @return The value of the field or method
   */
  public Object get(String name, Object[] scopes) {
    if (notfound) return null;
    if (returnThis) {
      return scopes[scopes.length - 1];
    }
    if (wrapper == null) {
      if (getWrapper(name, scopes)) return null;
    }
    try {
      return oh.coerce(wrapper.call(scopes));
    } catch (GuardException e) {
      wrapper = null;
      return get(name, scopes);
    }
  }

  private boolean getWrapper(String name, Object[] scopes) {
    wrapper = oh.find(name, scopes);
    if (wrapper == null) {
      notfound = true;
      if (debug) {
        // Ugly but generally not interesting
        if (!(this instanceof PartialCode)) {
          StringBuilder sb = new StringBuilder("Failed to find: ");
          sb.append(name).append(" (").append(tc.file()).append(":").append(tc.line()).append(") ").append("in");
          for (Object scope : scopes) {
            if (scope != null) {
              sb.append(" ").append(scope.getClass().getSimpleName());
            }
          }
          logger.warning(sb.toString());
        }
      }
      return true;
    }
    return false;
  }

  public Writer execute(Writer writer, Object scope) {
    return execute(writer, new Object[]{scope});
  }

  /**
   * The default behavior is to run the codes and append the captured text.
   *
   * @param writer The writer to write the output to
   * @param scopes The scopes to evaluate the embedded names against.
   */
  public Writer execute(Writer writer, Object[] scopes) {
    return appendText(runCodes(writer, scopes));
  }

  public void identity(Writer writer) {
    try {
      if (name != null) {
        tag(writer, type);
        if (getCodes() != null) {
          runIdentity(writer);
          tag(writer, "/");
        }
      }
      appendText(writer);
    } catch (IOException e) {
      throw new MustacheException(e);
    }
  }

  protected void runIdentity(Writer writer) {
    int length = getCodes().length;
    for (int i = 0; i < length; i++) {
      getCodes()[i].identity(writer);
    }
  }

  private void tag(Writer writer, String tag) throws IOException {
    writer.write(tc.startChars());
    writer.write(tag);
    writer.write(name);
    writer.write(tc.endChars());
  }

  protected Writer appendText(Writer writer) {
    if (appended != null) {
      try {
        writer.write(appended);
      } catch (IOException e) {
        throw new MustacheException(e);
      }
    }
    return writer;
  }

  protected Writer runCodes(Writer writer, Object[] scopes) {
    Code[] codes = getCodes();
    if (codes != null) {
      for (Code code : codes) {
        writer = code.execute(writer, scopes);
      }
    }
    return writer;
  }

  public void append(String text) {
    sb.append(text);
    appended = sb.toString();
  }

  private ThreadLocal<Object[]> localScopes = new ThreadLocal<Object[]>();

  /**
   * Allocating new scopes is currently the only place where we are activtely allocating
   * memory within the templating system. It is possible that recycling these might lend
   * some additional benefit or using the same one in each thread. The only time this
   * grows is when there are recursive calls to the same scope. In most non-degenerate cases
   * we won't encounter that. Also, since we are copying the results across these boundaries
   * we don't have to worry about threads.
   */
  protected Object[] addScope(Object next, Object[] scopes) {
    Object[] iteratorScopes = scopes;
    if (next != null) {
      // Need to expand the scopes holder
      iteratorScopes = localScopes.get();
      if (iteratorScopes == null) {
        iteratorScopes = new Object[scopes.length + 1];
        localScopes.set(iteratorScopes);
      } else {
        if (iteratorScopes.length < scopes.length + 1) {
          // Need to expand the scopes holder
          iteratorScopes = new Object[scopes.length + 1];
          localScopes.set(iteratorScopes);
        }
      }
      int srcPos = iteratorScopes.length - scopes.length - 1;
      System.arraycopy(scopes, 0, iteratorScopes, srcPos, scopes.length);
      for (; srcPos > 0; srcPos--) {
        iteratorScopes[srcPos - 1] = null;
      }
      iteratorScopes[iteratorScopes.length - 1] = next;
    }
    return iteratorScopes;
  }
}
