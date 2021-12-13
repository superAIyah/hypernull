package ru.croccode.hypernull.bot.debug;

import java.util.List;

public class output {
    public static <T> void InverseY(T[][] mas, int w, int h, String s) {
        System.out.println("<----- " + s + " ----->");
        for (int i = h - 1; i >= 0; i--) {
            for (int j = 0; j < w; j++)
                System.out.print(mas[i][j]);
            System.out.println();
        }
    }
}
