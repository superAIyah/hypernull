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

import static java.lang.Math.max;
import static java.lang.Math.min;

public class StarterBot implements Bot {

	private static final Random rnd = new Random(System.currentTimeMillis());

	private final MatchMode mode;

	private Offset moveOffset;

	private int moveCounter = 0;

	//MY PART OF BOT
	final int LEN_CYCLE = 3;
	final int DEPTH_COUNTING = 3;

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
		return vision[p.y()][p.x()];
	}

	int getPointID(Point p) { // легкий хеш для точки
		return (w * p.y()) + p.x();
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
				if (getVision(us.apply(next, mapSize)) != 0 && getVision(us.apply(next, mapSize)) != 3 ) // если это не блок и не бот
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
		ArrayList<Offset> vars = variants(us);
		ArrayList<Offset> clearVars = new ArrayList<>();
		for (Offset step : vars)
			if (clearWay(us, step))
				clearVars.add(step);
		Offset best = findBest(us, clearVars);
		Move move  = new Move();
		move.setOffset(best);
		return move;
	}

	void coinsBFS(Point us, int ourId, int[][] distCoins, Map<Point, Integer> ids) {
		int[][] dist = new int[h][w]; // локальная карта расстояний для bfs
		boolean[][] used = new boolean[h][w]; // использованный точки для bfs

		LinkedList<Point> queue = new LinkedList<>();
		queue.add(us); // стартовая точка
		used[us.y()][us.x()] = true;

		while (!queue.isEmpty()) {
			Point p1 = queue.poll();
			if (ids.containsKey(p1)) { // если мы находимся на точке, которая является монетой
				distCoins[ourId][ids.get(p1)] = dist[p1.y()][p1.x()];
			}
			ArrayList<Offset> variants = variants(p1); // варианты сдвигов, чтобы не попасть в блоки/ботов/клетки за видимостью
			for (Offset step : variants) {
				Point p2 = p1.apply(step, mapSize); // следующая вершина BFS
				if (!used[p2.y()][p2.x()]) {
					queue.add(p2);
					used[p2.y()][p2.x()] = true;
					dist[p2.y()][p2.x()] = dist[p1.y()][p1.x()] + 1;
				}
			}
		}
		System.out.println("BFS from" + us.toString() + " ID = " + ourId);
		System.out.println(Arrays.toString(distCoins[ourId]));
	}

	int SummaryDistance(Point p, int[][] distCoins, Map<Point, Integer> ids) {
		if (cycle.contains(p)) // чтобы не зациклиться
			return 1000;
		int n = distCoins[0].length;
		int[][] distFromBot2d = new int[1][n];
		coinsBFS(p, 0, distFromBot2d, ids);
		int[] distFromBot = distFromBot2d[0];
		int minDistance = 1000;

		// замнеить в будущем на рекурсивный перебор DEPTH_COUNTING точек 0_0
		for (int c1 = 0; c1 < n; c1++)
			for (int c2 = 0; c2 < n; c2++)
				for (int c3 = 0; c3 < n; c3++) {
					int dist = distFromBot[c1] + distCoins[c1][c2] + distCoins[c2][c3];
					minDistance = min(minDistance, dist);
				}
		return minDistance;
	}

	Move safeEat(Point us, Point[] coins) {
		if (coins == null || coins.length == 0)
			return explore(us);

		int n = coins.length;
		HashMap<Point, Integer> ids = new HashMap<Point, Integer>(); // проиндексируем каждую точку от 0 до size - 1
		for (int i = 0; i < n; i++)
			ids.put(coins[i], i);
		int[][] distCoins = new int[n][n]; // двуммерный массив расстояний

		// Пусть N - площадь обозреваемого квадрата (около 36)
		// переберем все монеты и найдем расстоние от каждой до всех O(N^2)
		for (int i = 0; i < n; i++) {
			coinsBFS(coins[i], i, distCoins, ids);
		}

		ArrayList<Offset> steps = variants(us); // варианты куда можно пойти
		Offset bestStep = new Offset(1, 1);
		int minDist = 1000;
		for (Offset step : steps) {
			Point p2 = us.apply(step, mapSize);
			int distTmp = SummaryDistance(p2, distCoins, ids);
			if (distTmp < minDist) {
				minDist = distTmp;
				bestStep = step;
			}
		}
		Move move = new Move();
		move.setOffset(bestStep);
		System.out.println("MIN DIST FOR GETTING 3 coins:");
		System.out.println(minDist);
		return move;


		// найдем расстояние от бота до каждой монеты
		/*int[][] distFromBot2d = new int[1][n];
		coinsBFS(us, 0, distFromBot2d, ids);
		int[] distFromBot = distFromBot2d[0];
		System.out.println("dist FROM BOR:");
		System.out.println(Arrays.toString(distFromBot));
		System.out.println("COINS!:");
		System.out.println(Arrays.toString(coins));
		System.out.println("EXPORED!:");
		for (int i = 0; i < n; i++) {
				System.out.println(Arrays.toString(distCoins[i]));
			System.out.println();
		}*/

		//return explore(us);
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
		System.out.println("ROUND ------> " + upd.getRound());
		Thread.sleep(1000);

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
		Move best = safeEat(us, coins);
		//Move best = explore(us);
		/*output.InverseY(vision, w, h, "VISION");
		System.out.println();
		output.InverseY(exploreMap, w, h, "EXPLORE MAP");*/
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
