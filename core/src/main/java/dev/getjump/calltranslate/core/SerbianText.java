package dev.getjump.calltranslate.core;

public final class SerbianText {
  private SerbianText() {}

  public static String toCyrillic(String text) {
    String latin = "abvgdđežzijk lmnoprstćufhcčš".replace(" ", "");
    String cyrillic = "абвгдђежзијклмнопрстћуфхцчш";
    StringBuilder output = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char letter = text.charAt(i);
      boolean upper = Character.isUpperCase(letter);
      String pair =
          i + 1 < text.length() ? text.substring(i, i + 2).toLowerCase(java.util.Locale.ROOT) : "";
      char combined =
          switch (pair) {
            case "lj" -> 'љ';
            case "nj" -> 'њ';
            case "dž" -> 'џ';
            default -> 0;
          };
      if (combined != 0) {
        output.append(upper ? Character.toUpperCase(combined) : combined);
        i++;
        continue;
      }
      int index = latin.indexOf(Character.toLowerCase(letter));
      char converted = index < 0 ? letter : cyrillic.charAt(index);
      output.append(upper ? Character.toUpperCase(converted) : converted);
    }
    return output.toString();
  }
}
