package aws;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Scanner;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClient;
import software.amazon.awssdk.services.elasticloadbalancing.model.CreateLoadBalancerRequest;
import software.amazon.awssdk.services.elasticloadbalancing.model.DeleteLoadBalancerRequest;
import software.amazon.awssdk.services.elasticloadbalancing.model.Listener;
import software.amazon.awssdk.services.elasticloadbalancing.model.RegisterInstancesWithLoadBalancerRequest;

public class LBScenario {
	
	public static void createLoadBalancer(ElasticLoadBalancingClient asc, String lbName, String secGroupId) {
		Listener lst = Listener.builder().instancePort(8080).instanceProtocol("tcp").
				loadBalancerPort(8080).protocol("tcp").build();
		CreateLoadBalancerRequest req = CreateLoadBalancerRequest.builder().
				availabilityZones("us-west-2a","us-west-2b").loadBalancerName(lbName).
				listeners(lst).securityGroups(secGroupId).build();
		asc.createLoadBalancer(req);
	}
	
	public static void registerInstancesWithLB(ElasticLoadBalancingClient asc, String lbName, List<String> instIds) {
		List<software.amazon.awssdk.services.elasticloadbalancing.model.Instance> insts = 
				new ArrayList<software.amazon.awssdk.services.elasticloadbalancing.model.Instance>();
		for (int i = 0; i < instIds.size(); i++) {
			String instId = instIds.get(i);
			software.amazon.awssdk.services.elasticloadbalancing.model.Instance ic = 
					software.amazon.awssdk.services.elasticloadbalancing.model.Instance.builder().instanceId(instId).build();
			insts.add(ic);
		}
		
		RegisterInstancesWithLoadBalancerRequest req = RegisterInstancesWithLoadBalancerRequest.builder().
				loadBalancerName(lbName).instances(insts).build();
		asc.registerInstancesWithLoadBalancer(req);
	}
	
	public static void deleteLoadBalancer(ElasticLoadBalancingClient asc, String lbName) {
		DeleteLoadBalancerRequest req = DeleteLoadBalancerRequest.builder().
				loadBalancerName(lbName).build();
		asc.deleteLoadBalancer(req);
	}
	
	public static byte[] getUserData(String dbHost) {
		String data = "#!/bin/bash\n";
		data += ("echo 'DB_HOST=" + dbHost + "' | sudo tee -a /etc/default/tomcat8\n");
		data += "sudo service tomcat8 restart";
		
		return Base64.getEncoder().encode(data.getBytes());
	}
	
	public static List<String> createInstances(Ec2Client ec2, int instNum, String imgId, String instType, 
			String keyPairName, String secGroupId, String dbHost) {
		List<String> instances = new ArrayList<String>();
		
		RunInstancesRequest runRequest = RunInstancesRequest.builder()
	             .imageId(imgId)
	             .instanceType(instType)
	             .maxCount(instNum)
	             .minCount(instNum)
	             .keyName(keyPairName)
	             .securityGroupIds(secGroupId)
	             .userData(new String(getUserData(dbHost)))
	             .build();
	     RunInstancesResponse response = ec2.runInstances(runRequest);
	
	     List<Instance> insts = response.instances();
	     if (insts != null) {
	    	 for (Instance inst: insts) instances.add(inst.instanceId());
	     }

		return instances;
	}
	
	public static Instance createDBInstance(Ec2Client ec2, String imgId, String instType, String keyPairName, 
			String secGroupId) {
		RunInstancesRequest runRequest = RunInstancesRequest.builder()
	             .imageId(imgId)
	             .instanceType(instType)
	             .maxCount(1)
	             .minCount(1)
	             .keyName(keyPairName)
	             .securityGroupIds(secGroupId)
	             .build();
	     RunInstancesResponse response = ec2.runInstances(runRequest);
	
	     List<Instance> insts = response.instances();
	     if (insts != null && !insts.isEmpty()) {
	    	 return insts.get(0);
	     }

		return null;
	}
	
	public static String getIpWhenReady(Ec2Client ec2, String instId) {
		String ip = null;
		while (ip == null) {
			DescribeInstancesRequest req = DescribeInstancesRequest.builder()
	             .instanceIds(instId)
	             .build();
			DescribeInstancesResponse response = ec2.describeInstances(req);
			if (response.hasReservations()) {
				for (Reservation res: response.reservations()) {
					if (res.hasInstances()) {
						Instance inst = res.instances().get(0);
						ip = inst.publicIpAddress();
						if (ip != null) break;
					}
					else break;
				}
			}
			else break;
			try {
				Thread.sleep(2000);
			}
			catch(Exception e) {
				
			}
		}

		return ip;
	}
	
	public static void deleteAllInstances(Ec2Client ec2, String dbInstance, List<String> restInstances) {
		restInstances.add(dbInstance);
		TerminateInstancesRequest req = TerminateInstancesRequest.builder().
	             instanceIds(restInstances).build();
	    ec2.terminateInstances(req);
	}
	
	public static void main(String[] args) {
		String lbName = "BookServiceLB";
		String secTomcatGroupId = "sg-0098decf0d51da053";
		String secMySQLGroupId = "sg-042e2ddde529d8a88";
		String imgIdRest = "ami-03744df9b3216a8e8";
		String imgIdMySQL = "ami-0f0c03219214d761f";
		String instType = "t2.micro";
		String keyPairName = "test_docker";
		String dbHost;
		
		//Create Ec2Client
		Ec2Client ec2 = Ec2Client.builder().region(Region.US_WEST_2).build();
		
		//Create dbInstance & retrieve dbHost IP
		Instance dbInstance = createDBInstance(ec2, imgIdMySQL, instType, keyPairName, secMySQLGroupId);
		if (dbInstance == null) {
			ec2.close();
			System.exit(-1);
		}
		dbHost = getIpWhenReady(ec2,dbInstance.instanceId());
		if (dbHost == null) {
			ec2.close();
			System.exit(-1);
		}
		
		//Create Book REST Service instances
		List<String> instances = createInstances(ec2,2,imgIdRest,instType,keyPairName,secTomcatGroupId,dbHost);
		if (instances == null || instances.isEmpty()) {
			ec2.close();
			System.exit(-1);
		}
		
		//Create Elastic load balancing client
		ElasticLoadBalancingClient asc = ElasticLoadBalancingClient.builder().region(Region.US_WEST_2).build();
		
		//Create load balancer and register only the Book Rest Service instances
		createLoadBalancer(asc,lbName,secTomcatGroupId);
		registerInstancesWithLB(asc,lbName,instances);
		
		//Wait to get user signal that there should be deletion of everything already created
		//If user presses Crtl-C nothing will be deleted
		Scanner sc = new Scanner(System.in);
		try {
			while (sc.hasNextLine()) {
				String str = sc.nextLine();
				if (str.trim().equals("end")) {
					deleteLoadBalancer(asc,lbName);
					deleteAllInstances(ec2,dbInstance.instanceId(),instances);
					break;
					//System.out.println("Got end!!!");
				}
			}
		}
		catch(Exception e) {	
		}
		finally {
			sc.close();
		}
		
		ec2.close();
		asc.close();
	}
}
