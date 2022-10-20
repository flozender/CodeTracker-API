package org.CodeTrackerAPI;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;

public class Hasher {

  public static void main(String[] args) throws IOException {
    String string1 = FileUtils.readFileToString(
      new File("src/main/java/org/CodeTrackerAPI/text1.txt"),
      "UTF-8"
    );
    String string2 = FileUtils.readFileToString(
      new File("src/main/java/org/CodeTrackerAPI/text2.txt"),
      "UTF-8"
    );
    System.out.println(string1.replaceAll(" ", "").replaceAll("\r\n", "").hashCode());
    System.out.println(string2.replaceAll(" ", "").replaceAll("\r\n", "").hashCode());
  }
}
