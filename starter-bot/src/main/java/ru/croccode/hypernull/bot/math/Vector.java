package ru.croccode.hypernull.bot.math;

import ru.croccode.hypernull.geometry.Point;

import java.util.Arrays;

public class Vector {
    static public final Point[] variants = new Point[]{new Point(-1, -1), new Point(0, -1), new Point(1, -1),
            new Point(-1, 1), new Point(0, 1), new Point(1, 1)};

    private double len(Point p) {
        return Math.sqrt(p.x() * p.x() + p.y() * p.y());
    }

    private double getCosOfVectors(Point p1, Point p2) {
        return (p1.x() * p2.x() + p1.y() * p2.y()) / len(p1) / len(p2);
    }

    public Point findClosest(Point a) { // чем ближе косинус между к векторами к 1 -> тем лучше
        double bestCos = -2;
        Point bestVec = variants[0];
        for (int i = 0; i < 6; i++) {
            if (getCosOfVectors(a, variants[i]) > bestCos) {
                bestCos = getCosOfVectors(a, variants[i]);
                bestVec = variants[i];
            }
        }
        return bestVec;
    }
}
