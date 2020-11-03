FROM amazonlinux

RUN yum install -y java-1.8.0-openjdk-devel
RUN curl -s https://bintray.com/sbt/rpm/rpm > /etc/yum.repos.d/bintray-sbt-rpm.repo
RUN yum install -y sbt-1.3.13
RUN sbt update
