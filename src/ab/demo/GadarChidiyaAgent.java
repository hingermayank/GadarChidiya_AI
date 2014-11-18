/*****************************************************************************
 ** ANGRYBIRDS AI AGENT FRAMEWORK
 ** Copyright (c) 2014, XiaoYu (Gary) Ge, Stephen Gould, Jochen Renz
 **  Sahan Abeyasinghe,Jim Keys,  Andrew Wang, Peng Zhang
 ** All rights reserved.
 **This work is licensed under the Creative Commons Attribution-NonCommercial-ShareAlike 3.0 Unported License.
 **To view a copy of this license, visit http://creativecommons.org/licenses/by-nc-sa/3.0/
 *or send a letter to Creative Commons, 444 Castro Street, Suite 900, Mountain View, California, 94041, USA.
 *****************************************************************************/
package ab.demo;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.*;

import ab.database.DBoperations;
import ab.demo.other.ActionRobot;
import ab.demo.other.Shot;
import ab.planner.TrajectoryPlanner;
import ab.regression.Datapoints;
import ab.utils.ABUtil;
import ab.utils.StateUtil;
import ab.vision.ABObject;
import ab.vision.ABType;
import ab.vision.GameStateExtractor.GameState;
import ab.vision.Vision;

public class GadarChidiyaAgent implements Runnable {

    public ActionRobot aRobot;
    private Random randomGenerator;
    public int currentLevel = 2;
    public static int time_limit = 12;
    private Map<Integer,Integer> scores = new LinkedHashMap<Integer,Integer>();
    TrajectoryPlanner tp;
    int order=0;
    private boolean firstShot;
    private Point prevTarget;
    public int chance_score=501;
    public int score_before=0;
    public int score_after=0;
    public int flag = 0;

    // a standalone implementation of the Naive Agent
    public GadarChidiyaAgent() {

        try {
            DBoperations dbop = new DBoperations();
        } catch (Exception e) {
            e.printStackTrace();
        }

        aRobot = new ActionRobot();
        tp = new TrajectoryPlanner();
        prevTarget = null;
        firstShot = true;
        randomGenerator = new Random();
        // --- go to the Poached Eggs episode level selection page ---
        ActionRobot.GoFromMainMenuToLevelSelection();

    }


    // run the client
    public void run() {

        aRobot.loadLevel(currentLevel);
        while (true) {
            GameState state = solve();
            if (state == GameState.WON) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                int score = StateUtil.getScore(ActionRobot.proxy);
                flag = 0;
                if(!scores.containsKey(currentLevel))
                    scores.put(currentLevel, score);
                else
                {
                    if(scores.get(currentLevel) < score)
                        scores.put(currentLevel, score);
                }
                int totalScore = 0;
                for(Integer key: scores.keySet()){

                    totalScore += scores.get(key);
                    System.out.println(" Level " + key
                            + " Score: " + scores.get(key) + " ");
                }
                System.out.println("Total Score: " + totalScore);

                aRobot.loadLevel(++currentLevel);
                // make a new trajectory planner whenever a new level is entered
                tp = new TrajectoryPlanner();

                // first shot on this level, try high shot first
                firstShot = true;

            } else if (state == GameState.LOST) {
                System.out.println("Restart");
                aRobot.restartLevel();
                flag=0;
            } else if (state == GameState.LEVEL_SELECTION) {
                System.out
                        .println("Unexpected level selection page, go to the last current level : "
                                + currentLevel);
                aRobot.loadLevel(currentLevel);
            } else if (state == GameState.MAIN_MENU) {
                System.out
                        .println("Unexpected main menu page, go to the last current level : "
                                + currentLevel);
                ActionRobot.GoFromMainMenuToLevelSelection();
                aRobot.loadLevel(currentLevel);
            } else if (state == GameState.EPISODE_MENU) {
                System.out
                        .println("Unexpected episode menu page, go to the last current level : "
                                + currentLevel);
                ActionRobot.GoFromMainMenuToLevelSelection();
                aRobot.loadLevel(currentLevel);
            }

        }

    }

    public double distance(Point p1, Point p2) {
        return Math
                .sqrt((double) ((p1.x - p2.x) * (p1.x - p2.x) + (p1.y - p2.y)
                        * (p1.y - p2.y)));
    }

    public GameState solve() {

        // capture Image
        BufferedImage screenshot = ActionRobot.doScreenShot();

        // process image
        Vision vision = new Vision(screenshot);
        Datapoints data = new Datapoints();
        // data.objectType(screenshot);

        // find the slingshot
        Rectangle sling = vision.findSlingshotMBR();

        // confirm the slingshot
        while (sling == null && aRobot.getState() == GameState.PLAYING) {
            System.out
                    .println("No slingshot detected. Please remove pop up or zoom out");
            ActionRobot.fullyZoomOut();
            screenshot = ActionRobot.doScreenShot();
            vision = new Vision(screenshot);
            sling = vision.findSlingshotMBR();
        }
        // get all the pigs
        List<ABObject> pigs = vision.findPigsRealShape();
        List<ABObject> blocks = vision.findBlocksRealShape();
        List<ABObject> objlist = vision.findBlocksRealShape();


        for (int i = 0; i < pigs.size(); i++) {
            objlist.add(pigs.get(i));
        }

        //  System.out.println("SIZE " + blocks.size());
        //System.out.println("NUMBER " + 1);
        //System.out.println("TYPE " + data.getTypes(blocks.get(0)));
        // System.out.println("AREA " + data.getArea(blocks.get(0)));
        // System.out.println("MIN PIG DISTANCE " + data.getMinPigDistance(blocks.get(0), pigs));
        // System.out.println("ABOVE BLOCKS WEIGHT " + data.aboveBlocksWeight(blocks.get(0), blocks));
        // System.out.println("ABOVE " + data.above(blocks.get(0), pigs));


        // sort the y-coordinates of the pigs
        Collections.sort(pigs, new sort_coordinates());

        /*List<BlockObject> obj = blockStructure(vision);
        for(int i=0;i<obj.size();i++){
            System.out.println(obj.get(i).getBlockNumber()+" "+obj.get(i).getBlockMaterial()+" "+obj.get(i).getBlockShape());
        }
*/


        GameState state = aRobot.getState();
        Datapoints dp = new Datapoints();
        ABType bird_onSling = aRobot.getBirdTypeOnSling();
        //   ArrayList<BlockObjectData> lists_block = new ArrayList<BlockObjectData>();



        // if there is a sling, then play, otherwise just skip.
        if (sling != null) {

            if (!pigs.isEmpty()) {

                Point releasePoint = null;

                Point pt = null;
                double chosenAngle = 45;
                int overallMaxScore = Integer.MIN_VALUE;
                Point finalSelectedPoint = null;
                Shot shot = new Shot();
                int dx, dy;
                {
                    // pick a pig at index 0 because pig with index 0 has the maximum height
                    // if the target is very close to before, randomly choose a
                    // point near it
                    Point tpt2 = new Point();
                    ABObject pig =pigs.get(0);
                    // tpt2.setLocation((pig.getCenter().x + pig.getWidth()) , (pig.getCenter().y + pig.getHeight()));
                    List<ABObject> support_blocks = ABUtil.getSupporters(pig , blocks);

                    List<ABObject> verticle_blocks2 = new ArrayList<ABObject>();

                    //Verticle blocks on left side of pig
                    List<ABObject> left_verticle_blocks = vision.findBlocksMBR();
                    for( int l=0 ; l < left_verticle_blocks.size() ; l++) {
                        if( left_verticle_blocks.get(l).getWidth() > left_verticle_blocks.get(l).getHeight() || left_verticle_blocks.get(l).getCenter().x >= pig.getCenter().x || left_verticle_blocks.get(l).getCenter().y > pig.getCenter().y || (left_verticle_blocks.get(l).getCenter().y + left_verticle_blocks.get(l).getHeight()/2) < (pig.getCenter().y-pig.getHeight()/2)) {
                            continue;
                        }
                        else {
                            verticle_blocks2.add(left_verticle_blocks.get(l));
                        }
                    }
                    Collections.sort(verticle_blocks2,new sort_coordinates_x());

                    if(support_blocks.size() == 0) {
                        // pig is on mountain
                        if(verticle_blocks2.size() == 0) {
                            tpt2 = pigs.get(0).getCenter();
                        }
                        else {
                            tpt2.setLocation(verticle_blocks2.get(0).getCenter().x, verticle_blocks2.get(0).getCenter().y - (verticle_blocks2.get(0).getHeight()/2));
                        }

                    }
                    else {

                        tpt2.setLocation(support_blocks.get(0).getCenter().x, support_blocks.get(0).getCenter().y );

                    }


                    if(chance_score >=0 && chance_score <=500 ){
                        flag = 1;
                    }

                    if(flag==0) {

                        for (int i = 0; i < objlist.size(); i++) {
                            ABObject currentObject = objlist.get(i);
                            //  BlockObjectData objBlock = new BlockObjectData();
                            int maxScore = Integer.MIN_VALUE;
                            Point center = currentObject.getCenter();
                            double aWeight = dp.aboveBlocksWeight(currentObject, objlist);
                            double distance = dp.getMinPigDistance(currentObject, pigs);
                            double pWeight = dp.getArea(currentObject);
                            double weakness = dp.getWeakness(currentObject, bird_onSling);


                            ArrayList<Double> angles = new ArrayList<Double>();
                            HashMap<Double, Point> map = new HashMap<Double, Point>();
                            ArrayList<Point> pts = tp.estimateLaunchPoint(sling, center);

                            for (int j = 0; j < pts.size(); j++) {
                                releasePoint = pts.get(j);
                                System.out.println("Initial Release Point: " + releasePoint);
                                if (releasePoint != null) {
                                    double releaseAngle = tp.getReleaseAngle(sling,
                                            releasePoint);
                                    angles.add(releaseAngle);
                                    map.put(releaseAngle, releasePoint);
                                }
                            }
                            int a = 0;
                            int b = 0;

                            if (currentObject.getType() == ABType.Pig) {
                                a = 1;
                                b = 1;
                            } else if (currentObject.getType() == ABType.Stone) {
                                a = 1;
                                b = 0;
                            }

                            for (int j = 0; j < angles.size(); j++) {
                                int score = (int) (-2046.0067 * a + 7540.1106 * b + -5824.481 * Math.toDegrees(angles.get(j)) / 100.0 + -1622.5479 * (pWeight / 1000.0) + -1264.6563 * (aWeight / 1000.0) + -989.9095 * distance + -4738.9017 * weakness + 13956.9713);
                                if (score > maxScore) {
                                    maxScore = score;
                                    if (chance_score >= 0 && chance_score <= 500 && angles.size() == 2) {
                                        if (angles.get(0) >= angles.get(1)) {
                                            chosenAngle = angles.get(1);
                                        } else {
                                            chosenAngle = angles.get(0);
                                        }
                                    } else {

                                        chosenAngle = angles.get(j);
                                    }
                                }
                            }
                            for (int k = 0; k < map.size(); k++) {
                                System.out.println("HAsHMAP DATA ANGLe , POINT : " + angles.get(k) + " " + map.get(angles.get(k)));
                            }
                            System.out.println("CHOSEN ANGLE : " + chosenAngle);
                            pt = map.get(chosenAngle);

                            System.out.println("Selected Release Point: " + pt);
                            System.out.println("Block Type : " + currentObject.getType());


                            // objBlock.setMaxScore(maxScore);
                            // objBlock.setRelAngle(chosenAngle);
                            // objBlock.setRelpoint(pt);
                            //  lists_block.add(objBlock);
                            if (maxScore >= overallMaxScore) {
                                overallMaxScore = maxScore;
                                finalSelectedPoint = pt;

                            }

                        }
                    }

                    else{
                        Point relPoint = null;
                        double chosenAngle2 = 0;

                        ArrayList<Point> weak_points = tp.estimateLaunchPoint(sling, tpt2);
                        ArrayList<Double> relAngles = new ArrayList<Double>();
                        HashMap<Double, Point> map2 = new HashMap<Double, Point>();
                        for (int j = 0; j < weak_points.size(); j++) {
                            relPoint = weak_points.get(j);

                            if (relPoint != null) {
                                double relAngle = tp.getReleaseAngle(sling,
                                        relPoint);
                                relAngles.add(relAngle);
                                map2.put(relAngle, relPoint);

                            }
                        }



                        System.out.println(weak_points.size());
                        System.out.println(relAngles.size());
                        if (relAngles.size() == 2 && flag==1) {

                            if (relAngles.get(0) >= relAngles.get(1)) {
                                chosenAngle2 = relAngles.get(1);
                            } else {
                                chosenAngle2 = relAngles.get(0);
                            }
                        } else {
                            if (relAngles.get(0) >= relAngles.get(1)) {
                                chosenAngle2 = relAngles.get(0);
                            } else {
                                chosenAngle2 = relAngles.get(1);
                            }
                        }

                        finalSelectedPoint = map2.get(chosenAngle2);

                        if(chance_score >=0 && chance_score <=500 ){
                            flag = 2;
                        }

                    }
                    System.out.println("FinAL High SCOREEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE : " + overallMaxScore);

                    Point refPoint = tp.getReferencePoint(sling);

                    if (finalSelectedPoint != null) {
                        System.out.println("Release Point: " + finalSelectedPoint);
                        System.out.println("Release Angle: "
                                + Math.toDegrees(chosenAngle));

                        int tapInterval = 0;

                        Point[] x_cord = new Point[objlist.size()];
                        for (int j = 0; j < objlist.size(); j++) {
                            x_cord[j] = objlist.get(j).getCenter();
                        }

                        Arrays.sort(x_cord, new Comparator<Point>() {
                            public int compare(Point a, Point b) {
                                int xComp = Integer.compare(a.x, b.x);
                                if (xComp == 0)
                                    return Integer.compare(a.y, b.y);
                                else
                                    return xComp;
                            }
                        });

                        Point left_most = x_cord[0];



                        switch (bird_onSling)
                        //switch (ABType.YellowBird)
                        {

                            case RedBird:
                                tapInterval = 0;
                                break;               // start of trajectory
                            case YellowBird:
                                tapInterval = 80 + randomGenerator.nextInt(10);
                                break; // 80-90% of the way
                            case WhiteBird:
                                tapInterval = 80 + randomGenerator.nextInt(10);
                                break; // 80-90% of the way
                            case BlackBird:
                                tapInterval = 75 + randomGenerator.nextInt(15);
                                break; // 75-90% of the way
                            case BlueBird:
                                tapInterval = 65 + randomGenerator.nextInt(15);
                                break; // 65-80% of the way
                            default:
                                tapInterval = 60;
                        }

                        int tapTime = tp.getTapTime(sling, finalSelectedPoint, left_most, tapInterval);
                        dx = (int) finalSelectedPoint.getX() - refPoint.x;
                        dy = (int) finalSelectedPoint.getY() - refPoint.y;

                        shot = new Shot(refPoint.x, refPoint.y, dx, dy, 0, tapTime);
                    } else {
                        System.err.println("No Release Point Found");
                        // releasePoint = tp.findReleasePoint(sling, Math.PI/4);
                        return state;
                    }


/*
                    ABObject pig = pigs.get(0);
					Point _tpt = pig.getCenter();// if the target is very close to before, randomly choose a
					// point near it
					if (prevTarget != null && distance(prevTarget, _tpt) < 10) {
						double _angle = randomGenerator.nextDouble() * Math.PI * 2;
						_tpt.x = _tpt.x + (int) (Math.cos(_angle) * 10);
						_tpt.y = _tpt.y + (int) (Math.sin(_angle) * 10);
						System.out.println("Randomly changing to " + _tpt);
					}
					prevTarget = new Point(_tpt.x, _tpt.y);
					// estimate the trajectory
					ArrayList<Point> pts = tp.estimateLaunchPoint(sling, _tpt);
                    // sorting x-coordinates to get the maximum angle and highest trajectory
                    Collections.sort(pts, new Comparator<Point>() {
                        @Override public int compare(Point p1, Point p2) {
                            return (int)(p1.getX()- p2.getX());
                        }
                    });
                    for(int l=0;l<pts.size();l++){
                        System.out.print("x"+pts.get(l).getX()+" y"+ pts.get(l).getY());
                    }
					// do a high shot when entering a level to find an accurate velocity
					if (firstShot && pts.size() > 1)
					{
						releasePoint = pts.get(pts.size()-1);
					}
					else if (pts.size() == 1)
						releasePoint = pts.get(0);
					else if (pts.size() == 2)
					{
						// randomly choose between the trajectories, with a 1 in
						// 6 chance of choosing the high one
                        releasePoint = pts.get(pts.size()-1);
					}
					else
						if(pts.isEmpty())
						{
							System.out.println("No release point found for the target");
							System.out.println("Try a shot with 45 degree");
							releasePoint = tp.findReleasePoint(sling, Math.PI/4);
						}
					// Get the reference point
					Point refPoint = tp.getReferencePoint(sling);
					//Calculate the tapping time according the bird type
					if (releasePoint != null) {
						double releaseAngle = tp.getReleaseAngle(sling,
								releasePoint);
						System.out.println("Release Point: " + releasePoint);
						System.out.println("Release Angle: "
								+ Math.toDegrees(releaseAngle));
						int tapInterval = 0;
						switch (aRobot.getBirdTypeOnSling())
						{
						case RedBird:
							tapInterval = 0; break;               // start of trajectory
						case YellowBird:
							tapInterval = 65 + randomGenerator.nextInt(25);break; // 65-90% of the way
						case WhiteBird:
							tapInterval =  70 + randomGenerator.nextInt(20);break; // 70-90% of the way
						case BlackBird:
							tapInterval =  70 + randomGenerator.nextInt(20);break; // 70-90% of the way
						case BlueBird:
							tapInterval =  65 + randomGenerator.nextInt(20);break; // 65-85% of the way
						default:
							tapInterval =  60;
						}
						int tapTime = tp.getTapTime(sling, releasePoint, _tpt, tapInterval);
						dx = (int)releasePoint.getX() - refPoint.x;
						dy = (int)releasePoint.getY() - refPoint.y;
						shot = new Shot(refPoint.x, refPoint.y, dx, dy, 0, tapTime);
					}
					else
						{
							System.err.println("No Release Point Found");
							return state;
						}
				}
                */
                    // check whether the slingshot is changed. the change of the slingshot indicates a change in the scale.

                    ActionRobot.fullyZoomOut();
                    screenshot = ActionRobot.doScreenShot();
                    vision = new Vision(screenshot);
                    Rectangle _sling = vision.findSlingshotMBR();
                    if (_sling != null) {
                        double scale_diff = Math.pow((sling.width - _sling.width), 2) + Math.pow((sling.height - _sling.height), 2);
                        if (scale_diff < 25) {
                            if (dx < 0) {
                                score_before = dp.getScore();
                                aRobot.cshoot(shot);
                                state = aRobot.getState();
                                if (state == GameState.PLAYING) {
                                    score_after = dp.getScore();
                                    chance_score = score_after - score_before;
                                    screenshot = ActionRobot.doScreenShot();
                                    vision = new Vision(screenshot);
                                    List<Point> traj = vision.findTrajPoints();
                                    tp.adjustTrajectory(traj, sling, finalSelectedPoint);
                                    firstShot = false;
                                }
                            }
                        } else
                            System.out.println("Scale is changed, can not execute the shot, will re-segement the image");
                    } else
                        System.out.println("no sling detected, can not execute the shot, will re-segement the image");
                }

            }

        }
        return state;
    }




    public static void main(String args[]) {

        GadarChidiyaAgent na = new GadarChidiyaAgent();
        if (args.length > 0)
            na.currentLevel = Integer.parseInt(args[0]);
        na.run();

    }

    // Returns a list of all the blocks on the screenshot with block number, block material and blockshape
    public List<BlockObject> blockStructure(Vision vision){
        List<ABObject> blocks = vision.findBlocksRealShape();
        List<BlockObject> blockObjects = new ArrayList<BlockObject>();
        BlockObject obj = new BlockObject();
        for(int i = 0; i<blocks.size();i++) {
            obj.setBlockNumber(i);
            obj.setBlockMaterial(blocks.get(i).getType());
            obj.setBlockShape(blocks.get(i).getFrame());
            blockObjects.add(obj);
        }
        return blockObjects;
    }
}

//Sorts the y-Coordinates of the pigs
class sort_coordinates implements Comparator<ABObject> {


    public int compare(ABObject ab1, ABObject ab2) {

        double y1 = ab1.getCenter().getY();
        double y2 = ab2.getCenter().getY();

        if (y1 >= y2)
        {
            return +1;
        }else{
            return -1;
        }
    }
}




class sort_coordinates_x implements Comparator<ABObject> {


    public int compare(ABObject ab1, ABObject ab2) {

        double x1 = ab1.getCenter().getX();
        double x2 = ab2.getCenter().getX();

        if (x2 >= x1)
        {
            return +1;
        }else{
            return -1;
        }
    }
}
