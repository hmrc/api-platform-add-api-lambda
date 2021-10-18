FROM amazonlinux

RUN yum install -y java-1.8.0-openjdk-devel
RUN curl -L https://www.scala-sbt.org/sbt-rpm.repo > /etc/yum.repos.d/sbt-rpm.repo
RUN yum install -y sbt-1.3.13
RUN sbt update
