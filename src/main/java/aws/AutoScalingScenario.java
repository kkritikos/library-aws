package aws;

import java.util.ArrayList;
import java.util.Scanner;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.CreateAutoScalingGroupRequest;
import software.amazon.awssdk.services.autoscaling.model.DeleteAutoScalingGroupRequest;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.autoscaling.model.PutScalingPolicyRequest;
import software.amazon.awssdk.services.autoscaling.model.PutScalingPolicyResponse;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupRequest;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.CloudWatchException;
import software.amazon.awssdk.services.cloudwatch.model.ComparisonOperator;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricAlarmRequest;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateLaunchTemplateRequest;
import software.amazon.awssdk.services.ec2.model.CreateLaunchTemplateResponse;
import software.amazon.awssdk.services.ec2.model.DeleteLaunchTemplateRequest;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.RequestLaunchTemplateData;
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClient;

public class AutoScalingScenario {
	
	private static String createLaunchTemplate(Ec2Client ec2, String templateName, String imgIdRest, 
			String instType, String keyPairName, String secTomcatGroupId, String dbHost) {
		RequestLaunchTemplateData data = RequestLaunchTemplateData.builder().
				imageId(imgIdRest).instanceType(instType).
				keyName(keyPairName).securityGroupIds(secTomcatGroupId).
				userData(new String(LBScenario.getUserData(dbHost))).
				build();
		CreateLaunchTemplateRequest req = CreateLaunchTemplateRequest.builder().launchTemplateName(templateName).
				launchTemplateData(data).build();
		CreateLaunchTemplateResponse resp = ec2.createLaunchTemplate(req);
		if (resp != null) return resp.launchTemplate().launchTemplateId();
		else return null;
	}
	
	public static void deleteLaunchTemplate(Ec2Client ec2, String templateName) {
		DeleteLaunchTemplateRequest req = DeleteLaunchTemplateRequest.builder().launchTemplateName(templateName).
				build();
		ec2.deleteLaunchTemplate(req);
	}
	
	public static void createAutoScalingGroup(AutoScalingClient asc, String groupName, 
			int desiredCapacity, int minCapacity, int maxCapacity, String lbName, String templateId) {
		LaunchTemplateSpecification spec = LaunchTemplateSpecification.builder().
				launchTemplateId(templateId).build();
		CreateAutoScalingGroupRequest req = CreateAutoScalingGroupRequest.builder().
				autoScalingGroupName(groupName).availabilityZones("us-west-2a","us-west-2b").
				healthCheckType("ELB").loadBalancerNames(lbName).
				desiredCapacity(desiredCapacity).minSize(minCapacity).
				maxSize(maxCapacity).launchTemplate(spec).
				healthCheckGracePeriod(300).
				build();
		asc.createAutoScalingGroup(req);
	}
	
	public static void updateAutoScalingGroup(AutoScalingClient asc, String groupName, int desiredCapacity, 
			int minCapacity, int maxCapacity) {
		UpdateAutoScalingGroupRequest req = UpdateAutoScalingGroupRequest.builder().
				autoScalingGroupName(groupName).desiredCapacity(desiredCapacity).minSize(minCapacity).
				maxSize(maxCapacity).build();
		asc.updateAutoScalingGroup(req);
	}
	
	public static String putScalingPolicy(AutoScalingClient asc, String scaleGroupName, String policyName, 
			int adjustment) {
		PutScalingPolicyRequest req = PutScalingPolicyRequest.builder().
				autoScalingGroupName(scaleGroupName).adjustmentType("ChangeInCapacity").cooldown(120).
				policyName(policyName).policyType("SimpleScaling").scalingAdjustment(adjustment).
				build();
		PutScalingPolicyResponse resp = asc.putScalingPolicy(req);
		if (resp != null) return resp.policyARN();
		else return null;
	}
	
	public static void putMetricAlarm(CloudWatchClient cw, String alarmName, String policyArn, String groupName, 
			double threshold){
	    try {
	        Dimension groupDimension = Dimension.builder()
	            .name("AutoScalingGroupName").value(groupName).build();
	        PutMetricAlarmRequest request = PutMetricAlarmRequest.builder()
	            .alarmName(alarmName)
	            .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
	            .evaluationPeriods(1).metricName("CPUUtilization")
	            .namespace("AWS/EC2").period(300)
	            .statistic(Statistic.AVERAGE).threshold(threshold)
	            .alarmActions(policyArn)
	            .alarmDescription(
	                    "Alarm when server CPU utilization exceeds 70%")
	            .dimensions(groupDimension).build();
	        cw.putMetricAlarm(request);
	        System.out.printf(
	                "Successfully created alarm with name %s", alarmName);
	    } catch (CloudWatchException e) {
	        System.err.println(e.awsErrorDetails().errorMessage());
	    }
	 }
	
	public static void deleteAutoscalingGroup(AutoScalingClient asc, String groupName) {
		DeleteAutoScalingGroupRequest req = DeleteAutoScalingGroupRequest.builder().
				autoScalingGroupName(groupName).forceDelete(true).build();
		asc.deleteAutoScalingGroup(req);
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
		String groupName = "BookServiceAutoScaler";
		String templateId;
		String templateName = "BookServiceLaunchTemplate";
		String policyArn;
		String policyName = "ScaleOutBookService";
		
		//Create Ec2Client
		Ec2Client ec2 = Ec2Client.builder().region(Region.US_WEST_2).build();
		
		//Create dbInstance & retrieve dbHost IP
		Instance dbInstance = LBScenario.createDBInstance(ec2, imgIdMySQL, instType, keyPairName, secMySQLGroupId);
		if (dbInstance == null) {
			ec2.close();
			System.exit(-1);
		}
		dbHost = LBScenario.getIpWhenReady(ec2,dbInstance.instanceId());
		if (dbHost == null) {
			ec2.close();
			System.exit(-1);
		}
		//dbHost = "34.221.171.104";
		
		//Create Elastic load balancing client
		ElasticLoadBalancingClient elbc = ElasticLoadBalancingClient.builder().region(Region.US_WEST_2).build();
		
		//Create load balancer and register only the Book Rest Service instances
		LBScenario.createLoadBalancer(elbc,lbName,secTomcatGroupId);
		
		//Create launch template
		templateId = createLaunchTemplate(ec2, templateName, imgIdRest, instType, keyPairName, secTomcatGroupId, dbHost);
		//templateId = "lt-06cf93792611fcf32";
		
		//Create AutoScalingClient
		AutoScalingClient asc = AutoScalingClient.builder()
                .region(Region.US_WEST_2)
                .build();
		
		//Create AutoScalingGroup
		createAutoScalingGroup(asc, groupName, 1, 1, 2, lbName, templateId);
		
		//Create Scaling Policy
		policyArn = putScalingPolicy(asc, groupName, policyName, 1);
		
		//Create AmazonWatchClient
		CloudWatchClient awc = CloudWatchClient.builder()
                .region(Region.US_WEST_2)
                .build();
		
		//Create CPU Utilization alarm
		putMetricAlarm(awc, lbName, policyArn, groupName, 70);
		
		//Wait to get user signal that there should be deletion of everything already created
		//If user presses Crtl-C nothing will be deleted
		Scanner sc = new Scanner(System.in);
		try {
			while (sc.hasNextLine()) {
				String str = sc.nextLine();
				if (str.trim().equals("end")) {
					deleteAutoscalingGroup(asc, groupName);
					LBScenario.deleteLoadBalancer(elbc,lbName);
					deleteLaunchTemplate(ec2, templateName);
					LBScenario.deleteAllInstances(ec2, dbInstance.instanceId(), new ArrayList<String>());
					break;
				}
			}
		}
		catch(Exception e) {
			e.printStackTrace();
		}
		finally {
			sc.close();
		}
		
		ec2.close();
		elbc.close();
		asc.close();
		awc.close();
	}
}
