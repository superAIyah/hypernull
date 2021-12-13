package ru.croccode.hypernull.bot;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.SQLOutput;
import java.util.*;
import ru.croccode.hypernull.bot.math.Vector;
import ru.croccode.hypernull.bot.debug.*;


import org.w3c.dom.ls.LSOutput;
import ru.croccode.hypernull.domain.MatchMode;
import ru.croccode.hypernull.geometry.Offset;
import ru.croccode.hypernull.geometry.Point;
import ru.croccode.hypernull.geometry.Size;
import ru.croccode.hypernull.io.SocketSession;
import ru.croccode.hypernull.message.Hello;
import ru.croccode.hypernull.message.MatchOver;
import ru.croccode.hypernull.message.MatchStarted;
import ru.croccode.hypernull.message.Move;
import ru.croccode.hypernull.message.Register;
import ru.croccode.hypernull.message.Update;

public class StarterBot implements Bot {

	private static final Random rnd = new Random(System.currentTimeMillis());

	private final MatchMode mode;

	private Offset moveOffset;

	private int moveCounter = 0;

	//MY PART OF BOT
	final int LEN_CYCLE = 3;

	MatchStarted ms; // вся инфа по матчу
	Integer[][] exploreMap; // карта исследователя: 0 - стена, 1 - свободно
	Integer[][] vision; // карта видимого: 0 - стена, 1 - свободно, 2 - монета, 3 - бот, 8 - мы
	Size mapSize;
	int w;
	int h;
	int vr;
	int vr2;
	LinkedList<Point> cycle;
	Point nextToExplore;

	void setVision(Point p, int type) { // обозначить объект
		vision[p.y()][p.x()] = type;
	}

	int getVision(Point p) { // получить объект по точке
		if (p.y() < 0 || p.x() < 0)
			System.out.println(p.x() + " ! " + p.y());
		return vision[p.y()][p.x()];
	}

	ArrayList<Point> visibleCoordinates(Point us) { // получаем список координат видимых точек
		ArrayList<Point> coords = new ArrayList<>();
		for (int i = 0; i < w; i++) {
			for (int j = 0; j < h; j++) {
				Point next = new Point(i, j);
				if (us.offsetTo(next, mapSize).length2() <= vr2) {
					coords.add(next);
				}
			}
		}
		return coords;
	}

	ArrayList<Offset> variants(Point us) { // всевозможные варианты шага от текущей точки
		ArrayList<Offset> vars = new ArrayList<>();
		for (int x = -1; x <= 1; x++) {
			for (int y = -1; y <= 1; y++) {
				if (x == 0 && y == 0)
					continue;
				Offset next = new Offset(x, y); // рассматриваем следующий шаг
				if (getVision(us.apply(next, mapSize)) != 0) // если это не блок
					vars.add(next); // добавим в возможные варианты хода
			}
		}
		return vars;
	}

	boolean clearWay(Point us, Offset step) { // будем обходить блоки на расстоянии vision / 2
		Point p2 = us.apply(step, mapSize);
		while (us.offsetTo(p2, mapSize).length2() <= vr2 / 2) {
			if (getVision(p2) == 0) { // если видим блок на прямой этого пути
				return false; // то путь по данному смещению не чист
			}
			p2 = p2.apply(step, mapSize);
		}
		return true;
	}

	Offset findBest(Point us, ArrayList<Offset> steps) { // найти лучший путь простым перебором по всем шагам
		int mindist = 1000;
		Offset bestStep = steps.get(0);
		for (Offset step : steps) {
			Point p2 = us.apply(step, mapSize);
			int dist = p2.offsetTo(nextToExplore, mapSize).length2();
			if (dist < mindist && !cycle.contains(p2)) {
				mindist = dist;
				bestStep = step;
			}
		}
		return bestStep;
	}

	Move explore(Point us) { // команда "исследовать" карту
		int mindist = 1000;
		nextToExplore = new Point(0, 0);
		for (int i = 0; i < h; i++) { // найдем самую близкую неизведанную точку
			for (int j = 0; j < w; j++) {
				Point p2 = new Point(j, i);
				int dist = us.offsetTo(p2, mapSize).length2();
				if (exploreMap[i][j] == 0 && dist < mindist && !cycle.contains(p2)) {
					mindist = dist;
					nextToExplore = p2;
				}
			}
		}
		System.out.println(nextToExplore);
		ArrayList<Offset> vars = variants(us);
		System.out.println("VARIANTS FUNC:");
		System.out.println(vars.size());
		ArrayList<Offset> clearVars = new ArrayList<>();
		for (Offset step : vars)
			if (clearWay(us, step))
				clearVars.add(step);
		Offset best = findBest(us, clearVars);
		Move move  = new Move();
		move.setOffset(best);
		return move;

	}

	public StarterBot(MatchMode mode) {
		this.mode = mode;
	}

	@Override
	public Register onHello(Hello hello) {
		Register register = new Register();
		register.setMode(mode);
		register.setBotName("starter-bot");
		return register;
	}

	@Override
	public void onMatchStarted(MatchStarted matchStarted) { // ---CHANGE---
		ms = matchStarted; // сохраним всю информацию

		vr = matchStarted.getViewRadius();
		vr2 = vr * vr;
		w = matchStarted.getMapSize().width(); // создаем крату исследователя
		h = matchStarted.getMapSize().height();
		mapSize = matchStarted.getMapSize();
		exploreMap = new Integer[h][w];
		vision = new Integer[h][w];
		for (int i = 0; i < h; i++)
			for (int j = 0; j < w; j++)
				Arrays.fill(exploreMap[i], 0);
		cycle = new LinkedList<>();
		for (int i = 0; i < LEN_CYCLE; i++)
			cycle.add(new Point(-1, -1));
	}

	@Override
	public Move onUpdate(Update upd) throws InterruptedException { // ---CHANGE---
		// будем в программе работать с перевернутой картой по Oy
		// в конце необходимо будет отразить вектор перемещения относительно Oy

		Thread.sleep(300);

		// извлекаем данные в зоне видимости
		Point[] blocks = (upd.getBlocks() != null) ? upd.getBlocks().toArray(new Point[0]) : new Point[]{};
		Point[] coins = (upd.getCoins() != null) ? upd.getCoins().toArray(new Point[0]) : new Point[]{};
		Map<Integer, Point> bots= upd.getBots(); // всегда будет хотя бы один бот - наш
		Point us = bots.get(0);
		ArrayList<Point> visible = visibleCoordinates(us);

		// обновим последние три хода
		cycle.add(us);
		cycle.remove();

		// очистим зону видимости
		for (int i = 0; i < h; i++)
			for (int j = 0; j < w; j++)
				Arrays.fill(vision[i], 0);


		for (Point p : visible) {
			setVision(p, 1); // пусть изначально мы видим только пустые клетки
			exploreMap[p.y()][p.x()] = 1; // отмечаем изученные клетки
		}
		for (Point p : blocks) { // обозначаем блоки
			setVision(p, 0);
		}
		for (Point p : coins) { // обозначаем монеты
			setVision(p, 2);
		}
		for (Integer key : bots.keySet()) { // обозначаем других ботов
			setVision(bots.get(key), 3);
		}
		setVision(us, 8);
		Move best = explore(us);
		System.out.println("CHANGES");
		System.out.println(best.getOffset().dx());
		System.out.println(best.getOffset().dy());
		System.out.println("OK");
		int sum = 0;
		for (Integer[] mas : exploreMap)
			for (int a : mas)
				sum += a;
		System.out.println("TOTAL FOUND: "+sum);
		output.InverseY(vision, w, h, "VISION");
		System.out.println();
		output.InverseY(exploreMap, w, h, "EXPLORE MAP");
		return best;
		/*if (moveOffset == null || moveCounter > 5 + rnd.nextInt(5)) {
			moveOffset = new Offset(
					rnd.nextInt(3) - 1,
					rnd.nextInt(3) - 1
			);
			moveCounter = 0;
		}
		moveCounter++;
		Move move = new Move();
		move.setOffset(moveOffset);
		return move;*/
	}

	@Override
	public void onMatchOver(MatchOver matchOver) {
	}

	public static void main(String[] args) throws IOException {
		Socket socket = new Socket();
		socket.setTcpNoDelay(true);
		socket.setSoTimeout(300_000); // 10 -> 3
		socket.connect(new InetSocketAddress("localhost", 2021));

		SocketSession session = new SocketSession(socket);
		StarterBot bot = new StarterBot(MatchMode.FRIENDLY);
		new BotMatchRunner(bot, session).run();
	}
}
