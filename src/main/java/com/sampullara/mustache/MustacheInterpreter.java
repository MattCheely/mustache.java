package com.sampullara.mustache;

import com.sampullara.util.FutureWriter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TODO: Edit this
 * <p/>
 * User: sam
 * Date: 5/14/11
 * Time: 3:52 PM
 */
public class MustacheInterpreter {

  private final Class<? extends Mustache> superclass;
  private final File root;

  public MustacheInterpreter(File root) {
    this.root = root;
    superclass = null;
  }

  public MustacheInterpreter(Class<? extends Mustache> superclass, File root) {
    this.superclass = superclass;
    this.root = root;
  }

  private Mustache newMustache() throws MustacheException {
    if (superclass == null) {
      return new Mustache() {
        @Override
        public void execute(FutureWriter writer, Scope ctx) throws MustacheException {
        }
      };
    }
    try {
      return superclass.newInstance();
    } catch (Exception e) {
      throw new MustacheException("Could not create superclass", e);
    }
  }

  public static interface Code {
    void execute(FutureWriter fw, Scope scope) throws MustacheException;
  }

  public void execute(List<Code> codes, FutureWriter fw, Scope scope) throws MustacheException {
    for (Code code : codes) {
      code.execute(fw, scope);
    }
  }

  public List<Code> compile(final Reader br) throws MustacheException {
    return compile(br, null, new AtomicInteger(0));
  }

  public List<Code> compile(final Reader br, String tag, final AtomicInteger currentLine) throws MustacheException {
    final List<Code> list = new ArrayList<Code>();
    // Base level
    final Mustache m = newMustache();

    // Now we grab the mustache template
    String sm = "{{";
    String em = "}}";

    int c;
    boolean onlywhitespace = true;
    boolean iterable = currentLine.get() != 0;
    currentLine.compareAndSet(0, 1);
    StringBuilder out = new StringBuilder();
    try {
      while ((c = br.read()) != -1) {
        if (c == '\r') {
          continue;
        }
        // Increment the line
        if (c == '\n') {
          currentLine.incrementAndGet();
          if (!iterable || (iterable && !onlywhitespace)) {
            out.append("\n");
          }

          iterable = false;
          onlywhitespace = true;
          continue;
        }
        // Check for a mustache start
        if (c == sm.charAt(0)) {
          br.mark(1);
          if (br.read() == sm.charAt(1)) {
            write(list, out);
            out = new StringBuilder();
            // Two mustaches, now capture command
            StringBuilder sb = new StringBuilder();
            while ((c = br.read()) != -1) {
              br.mark(1);
              if (c == em.charAt(0)) {
                if (br.read() == em.charAt(1)) {
                  // Matched end
                  break;
                } else {
                  // Only one
                  br.reset();
                }
              }
              sb.append((char) c);
            }
            final String command = sb.toString().trim();
            final char ch = command.charAt(0);
            final String variable = command.substring(1);
            switch (ch) {
              case '#':
              case '^':
              case '?': {
                int start = currentLine.get();
                final List<Code> codes = compile(br, variable, currentLine);
                list.add(new Code() {
                  @Override
                  public void execute(FutureWriter fw, Scope scope) throws MustacheException {
                    Iterable<Scope> iterable = null;
                    switch (ch) {
                      case '#':
                        iterable = m.iterable(scope, variable);
                        break;
                      case '^':
                        iterable = m.inverted(scope, variable);
                        break;
                      case '?':
                        iterable = m.ifiterable(scope, variable);
                        break;
                    }
                    for (final Scope subScope : iterable) {
                      try {
                        fw.enqueue(new Callable<Object>() {
                          @Override
                          public Object call() throws Exception {
                            FutureWriter fw = new FutureWriter();
                            for (Code code : codes) {
                              code.execute(fw, subScope);
                            }
                            return fw;
                          }
                        });
                      } catch (IOException e) {
                        throw new MustacheException("Failed to enqueue", e);
                      }
                    }
                  }
                });
                iterable = (currentLine.get() - start) != 0;
                break;
              }
              case '/': {
                // Tag end
                write(list, out);
                if (!variable.equals(tag)) {
                  throw new MustacheException("Mismatched start/end tags: " + tag + " != " + variable);
                }
                return list;
              }
              case '>': {
                final List<Code> codes = compile(new BufferedReader(new FileReader(new File(root, variable + ".html"))), null, currentLine);
                list.add(new Code() {
                  @Override
                  public void execute(FutureWriter fw, final Scope scope) throws MustacheException {
                    try {
                      fw.enqueue(new Callable<Object>() {
                        @Override
                        public Object call() throws Exception {
                          FutureWriter fw = new FutureWriter();
                          for (Code code : codes) {
                            code.execute(fw, scope);
                          }
                          return fw;
                        }
                      });
                    } catch (IOException e) {
                      throw new MustacheException("Failed to write", e);
                    }
                  }
                });
                break;
              }
              case '{': {
                // Not escaped
                String name = variable;
                if (em.charAt(1) != '}') {
                  name = variable.substring(0, variable.length() - 1);
                } else {
                  if (br.read() != '}') {
                    throw new MustacheException("Improperly closed variable");
                  }
                }
                final String finalName = name;
                list.add(new Code() {
                  @Override
                  public void execute(FutureWriter fw, Scope scope) throws MustacheException {
                    Object o = scope.get(finalName);
                    if (o != null) {
                      try {
                        fw.write(o.toString());
                      } catch (IOException e) {
                        throw new MustacheException("Failed to write", e);
                      }
                    }
                  }
                });
                break;
              }
              case '&': {
                // Not escaped
                list.add(new Code() {
                  @Override
                  public void execute(FutureWriter fw, Scope scope) throws MustacheException {
                    Object o = scope.get(variable);
                    if (o != null) {
                      try {
                        fw.write(o.toString());
                      } catch (IOException e) {
                        throw new MustacheException("Failed to write", e);
                      }
                    }
                  }
                });
                break;
              }
              case '%':
                // Pragmas
                break;
              case '!':
                // Comment
                break;
              default: {
                // Reference
                list.add(new Code() {
                  @Override
                  public void execute(FutureWriter fw, Scope scope) throws MustacheException {
                    Object o = scope.get(command);
                    if (o != null) {
                      try {
                        fw.write(Mustache.encode(o.toString()));
                      } catch (IOException e) {
                        throw new MustacheException("Failed to write", e);
                      }
                    }
                  }
                });
                break;
              }
            }
            continue;
          } else {
            // Only one
            br.reset();
          }
        }
        onlywhitespace = (c == ' ' || c == '\t') && onlywhitespace;
        if (!onlywhitespace) {
          out.append((char) c);
        }
      }
      write(list, out);
    } catch (IOException e) {
      throw new MustacheException("Failed to read", e);
    }
    return list;
  }

  private void write(List<Code> list, StringBuilder out) {
    final String rest = out.toString();
    list.add(new Code() {
      public void execute(FutureWriter fw, Scope scope) throws MustacheException {
        try {
          fw.write(rest);
        } catch (IOException e) {
          throw new MustacheException("Failed to write", e);
        }
      }
    });
  }
}
