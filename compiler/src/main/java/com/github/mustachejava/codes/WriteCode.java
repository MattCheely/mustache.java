package com.github.mustachejava.codes;

import java.io.IOException;
import java.io.Writer;

import com.github.mustachejava.Code;
import com.github.mustachejava.MustacheException;

/**
* Created by IntelliJ IDEA.
* User: spullara
* Date: 1/9/12
* Time: 3:13 PM
* To change this template use File | Settings | File Templates.
*/
public class WriteCode extends Code {
  private String text;

  public WriteCode(String text) {
    this.text = text;
  }

  @Override
  public void identity(Writer writer) {
    execute(writer, null);
  }

  @Override
  public void append(String text) {
    this.text += text;
  }

  @Override
  public Writer execute(Writer writer, Object[] scopes) {
    try {
      writer.write(text);
    } catch (IOException e) {
      throw new MustacheException();
    }
    return writer;
  }
}
