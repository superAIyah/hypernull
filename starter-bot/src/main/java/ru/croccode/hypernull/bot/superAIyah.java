package ru.croccode.hypernull.bot;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;

import ru.croccode.hypernull.bot.debug.*;


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

public class superAIyah implements Bot {

	private static final Random rnd = new Random(System.currentTimeMillis());

	private final MatchMode mode;

	private Offset moveOffset;

	private int moveCounter = 0;
	//info from matchStarted
	Point[] blocks;
	Point[] coins;
	Map<Integer, Point> bots;

	//MY PART OF BOT
	final int LEN_CYCLE = 10;
	final int DEPTH_COUNTING = 3;
	final int PERIOD = 30;
	final int TRAVEL_STEPS = 7;

	MatchStarted ms; // вся инфа по матчу
	Integer[][] exploreMap; // карта исследователя: 0 - стена, 1 - свободно
	Integer[][] vision; // карта видимого: 0 - стена, 1 - свободно, 2 - монета, 3 - бот, 8 - мы
	//взвешивание:
	int[][] weightedMap;
	Size mapSize;
	int w;
	int h;
	int vr;
	int vr2;
	int mr;
	int mr2;
	LinkedList<Point> cycle;
	Point nextToExplore;
	//deathmatch
	MatchMode DEATHMATCH;
	int myId;
	int myCoins;
	int huntSteps;
	Map<Integer, Point> botsCoordinates;
	Map<Integer, Integer> botsCoins;
	ArrayList<Point> nearby;

	boolean timeToTravel(int num) {
		return  (num % (PERIOD + TRAVEL_STEPS) >= PERIOD);
	}

	boolean firstTimeTravel(int num) {
		return (num % (PERIOD + TRAVEL_STEPS) == PERIOD);
	}

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
				if (us.offsetTo(next, mapSize).length2() <= mr2) {
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
		int mindist = 1001;
		Offset bestStep = steps.get(0);
		for (Offset step : steps) {
			Point p2 = us.apply(step, mapSize);
			int dist = p2.offsetTo(nextToExplore, mapSize).length2();
			if (mindist == 1001 || (dist < mindist && !cycle.contains(p2))) {
				mindist = dist;
				bestStep = step;
			}
		}
		return bestStep;
	}

	Move explore(Point us, Point farestWeighted) { // команда "исследовать" карту
		int mindist = 1000;
		nextToExplore = new Point(0, 0);
		if (farestWeighted == null) // если мы хотим не взвешенно найти самую удаленную точку
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
		else {
			nextToExplore = farestWeighted; // точка во взвшенное удаленной области
		}
		ArrayList<Offset> vars = variants(us);
		ArrayList<Offset> clearVars = new ArrayList<>();
		for (Offset step : vars)
			if (clearWay(us, step))
				clearVars.add(step);
		if (clearVars.isEmpty())
			clearVars.addAll(vars);

		Offset best = findBest(us, clearVars);
		Move move  = new Move();
		move.setOffset(best);
		return move;
	}

	// универсальный бфс: us - стартовая позиция | ourId - индекс в массиве расстояний distCoins |
	void coinsBFS(Point us, int ourId, int[][] distCoins, Map<Point, Integer> ids) {
		int[][] dist = new int[h][w]; // локальная карта расстояний для bfs
		boolean[][] used = new boolean[h][w]; // использованный точки для bfs

		LinkedList<Point> queue = new LinkedList<>();
		queue.add(us); // стартовая точка
		used[us.y()][us.x()] = true;

		while (!queue.isEmpty()) {
			Point p1 = queue.poll();
			for (Point p : ids.keySet()) {
				if (p.offsetTo(p1, mapSize).length2() <= mr2)
					distCoins[ourId][ids.get(p)] = dist[p1.y()][p1.x()];
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
	}

	int gen(int last, int n, int sum,
			int[][] distCoins, int[] distFromBot, boolean[] used) {
		int minDist = 1000;
		if (n == 0) {
			return sum;
		}
		for (int i = 0; i < n; i++) {
			if (used[i])
				continue;
			// добавляем
			used[i] = true;
			if (last == -1) // шагаем от начальной точки к монете
				minDist = min(minDist, gen(i, n - 1, sum + distFromBot[i], distCoins, distFromBot, used));
			else // шагаем от монеты к монете
				minDist = min(minDist, gen(i, n - 1, sum + distCoins[last][i], distCoins, distFromBot, used));
			used[i] = false;
		}
		return minDist;
	}

	int SummaryDistance(Point p, int[][] distCoins, Map<Point, Integer> ids) {
		if (cycle.contains(p)) // чтобы не зациклиться
			return 1000;
		int n = distCoins[0].length;
		int[][] distFromBot2d = new int[1][n];
		coinsBFS(p, 0, distFromBot2d, ids); // определим расстояние от бота до всех монет
		int[] distFromBot = distFromBot2d[0];

		int depth = min(n, DEPTH_COUNTING); // кол-во монет на которое мы просчитываем вперед
		return gen(-1, depth, 0, distCoins, distFromBot, new boolean[n]);
	}

	Move safeEat(Point us, Point[] coins) {
		if (coins == null || coins.length == 0) // если поблизости нет монет, исследуем ближайшую область
			return explore(us, null);

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
		int minDist = 1001;
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
		return move;
	}

	void fillWeightedMap(Point us) { // добавим веса точкам, в которых не были; обнулим посещеннын
		for (int i = 0; i < h; i++) {
			for (int j = 0; j < w; j++) {
				int length2 = us.offsetTo(new Point(j, i), mapSize).length2();
				if (length2 <= mr2)
					weightedMap[i][j] = 0;
				else if (length2 <= vr2)
					weightedMap[i][j] = 1;
				else
					weightedMap[i][j] += 1; // точки, которые мы не видели, становятся более актуальными
			}
		}
	}

	Point weightedPoint() { // найти центр самой удаленной области
		int bestSum = -1;
		Point bestPoint = new Point(0, 0);
		for (int i = 0; i < h; i++) {
			for (int j = 0; j < w; j++) {
				Point us = new Point(j, i);
				ArrayList<Point> visible = visibleCoordinates(us);
				int sumTmp = 0;
				for (Point p : visible)
					sumTmp += weightedMap[p.y()][p.x()];
				if (sumTmp > bestSum) {
					bestSum = sumTmp;
					bestPoint = us;
				}
			}
		}
		return bestPoint;
	}

	void fillVisionExplore(Point us) {
		// очистим зону видимости
		for (int i = 0; i < h; i++)
			for (int j = 0; j < w; j++)
				Arrays.fill(vision[i], 0);

		//обновим взвешенную карту мест посещения
		ArrayList<Point> visible = visibleCoordinates(us);

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
	}

	ArrayList<Integer> getDangerous(Point us) {
		ArrayList<Integer> ids = new ArrayList<>();
		for (Integer id : botsCoordinates.keySet()) {
			if (id == myId)
				continue;
			if (botsCoins.get(id) > myCoins)
				ids.add(id);
		}
		return ids;
	}

	ArrayList<Integer> numTarget(Point us) {
		ArrayList<Integer> ids = new ArrayList<>();
		for (Integer id : botsCoordinates.keySet()) {
			if (id == myId)
				continue;
			if (botsCoins.get(id) < myCoins)
				ids.add(id);
		}
		return ids;
	}

	// универсальный бфс: us - стартовая позиция | ourId - индекс в массиве расстояний distCoins |
	//void coinsBFS(Point us, int ourId, int[][] distCoins, Map<Point, Integer> ids) {
	int countDistBots(Point p, ArrayList<Integer> danger) { // посчитать сумму расстояний от точки до ботов
		int n = danger.size();
		int[][] distBots = new int[1][n];
		Map<Point, Integer> ids = new HashMap<>();
		ArrayList<Point> bots = new ArrayList<>();
		for (int key : danger) {
			bots.add(botsCoordinates.get(key));
		}
		for (int i = 0; i < n; i++)
			ids.put(bots.get(i), i);
		coinsBFS(p, 0, distBots, ids);
		int sum = 0;
		for (int i = 0; i < n; i++)
			sum += distBots[0][i];
		return sum;
	}

	Move run(Point us, ArrayList<Integer> danger) {
		ArrayList<Offset> vars = variants(us); // все легальные варианты шага
		ArrayList<Offset> clearVars = new ArrayList<>(); // все варианты шага, чтобы не идти в сторону стен
		for (Offset step : vars)
			if (clearWay(us, step))
				clearVars.add(step);
		if (clearVars.isEmpty()) // если все шаги на пути к стенам, то выберем из всех клеток
			clearVars.addAll(vars);

		int maxDist = -1; // сумма расстояний до опасных ботов
		Offset bestStep = new Offset(1, 1); // шаг, чтобы убежать от опасности
		for (Offset step : clearVars) {
			Point p2 = us.apply(step, mapSize);
			int dangerTmp = countDistBots(p2, danger); // опасность как сумма расстояний до опасных ботов
			if (dangerTmp > maxDist) {
				maxDist = dangerTmp;
				bestStep = step;
			}
		}
		Move move = new Move();
		move.setOffset(bestStep);
		return move;
	}

	Move hunt(Point us, ArrayList<Integer> target) {
		ArrayList<Offset> vars = variants(us); // все легальные варианты шага
		ArrayList<Offset> clearVars = new ArrayList<>(); // все варианты шага, чтобы не идти в сторону стен
		for (Offset step : vars)
			if (clearWay(us, step))
				clearVars.add(step);
		if (clearVars.isEmpty()) // если все шаги на пути к стенам, то выберем из всех клеток
			clearVars.addAll(vars);

		int minDist = 1000; // сумма расстояний до добычи
		Offset bestStep = new Offset(1, 1); // шаг, чтобы настичь жертву
		for (Offset step : clearVars) {
			Point p2 = us.apply(step, mapSize);
			int dangerTmp = countDistBots(p2, target); // потенциал атаки как сумма расстояний до жертв
			if (dangerTmp < minDist) {
				minDist = dangerTmp;
				bestStep = step;
			}
		}
		Move move = new Move();
		move.setOffset(bestStep);
		return move;
	}

	public superAIyah(MatchMode mode) {
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
	public void onMatchStarted(MatchStarted matchStarted) {
		ms = matchStarted; // сохраним всю информацию
		DEATHMATCH = matchStarted.getMode();
		int huntSteps = 0;
		vr = matchStarted.getViewRadius();
		vr2 = vr * vr;
		mr = matchStarted.getMiningRadius();
		mr2 = mr * mr;
		w = matchStarted.getMapSize().width(); // создаем крату исследователя
		h = matchStarted.getMapSize().height();
		mapSize = matchStarted.getMapSize();
		exploreMap = new Integer[h][w];
		vision = new Integer[h][w];
		weightedMap = new int[h][w];
		myId = matchStarted.getYourId();
		for (int i = 0; i < h; i++)
			for (int j = 0; j < w; j++)
				Arrays.fill(exploreMap[i], 0);
		cycle = new LinkedList<>();
		for (int i = 0; i < LEN_CYCLE; i++)
			cycle.add(new Point(0, 0));;
	}

	@Override
	public Move onUpdate(Update upd) throws InterruptedException { // ---CHANGE---
		// будем в программе работать с перевернутой картой по Oy
		// в конце необходимо будет отразить вектор перемещения относительно Oy
		//System.out.println("ROUND ------> " + upd.getRound());
		//Thread.sleep(1000);

		// извлекаем данные в зоне видимости
		blocks = (upd.getBlocks() != null) ? upd.getBlocks().toArray(new Point[0]) : new Point[]{};
		coins = (upd.getCoins() != null) ? upd.getCoins().toArray(new Point[0]) : new Point[]{};
		bots= upd.getBots(); // всегда будет хотя бы один бот - наш
		Point us = bots.get(0);

		// обновим последние LEN_CYCLE ходов
		cycle.add(us);
		cycle.remove();

		// заполним карту исследования и видимости
		fillVisionExplore(us);

		//заполним взвешенную карту посещений
		fillWeightedMap(us);

		//DEATHMATCH PART
		if (!mode.toString().equals("FRIENDLY")) {
			myCoins = upd.getBotCoins().get(myId);
			botsCoins = upd.getBotCoins();
			botsCoordinates = upd.getBots();
			for (Integer id : botsCoordinates.keySet())
				if (id != myId)
					nearby.add(botsCoordinates.get(id));
			ArrayList<Integer> danger = getDangerous(us);
			ArrayList<Integer> target = numTarget(us);
			if (!danger.isEmpty() && huntSteps < 12) {
				huntSteps++; // не будем гоняться за жертвой дольше 12 ходов
				return run(us, danger);
			}
			huntSteps = 0;
			if (!target.isEmpty())
				return hunt(us, target);
		}

		//если время путешествий - значит найдем самую непосещенную область и пройдем туда TRAVEL_STEPS шагов
		if (timeToTravel(upd.getRound())) {
			boolean target = true;
			Move best;
			if (firstTimeTravel(upd.getRound())) {
				int rand = (int) (Math.random() * 100);
				target = (rand % 2 == 0);
			}
			if (target) {
				Point farest = weightedPoint();
				best = explore(us, farest); // идем в еще неисследованную территорию
			} else {
				best = explore(us, null); // выбираем ближайшую неисследованную точку
			}
			return best;
		}
		Move best = safeEat(us, coins);
		return best;
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
		superAIyah bot = new superAIyah(MatchMode.FRIENDLY);
		new BotMatchRunner(bot, session).run();
	}
}
